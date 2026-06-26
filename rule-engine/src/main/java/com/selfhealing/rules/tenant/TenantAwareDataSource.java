package com.selfhealing.rules.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Wraps the pooled {@link DataSource} so each borrowed connection has
 * {@code app.current_tenant} set from {@link TenantContext} before use, and
 * cleared when returned to the pool. Postgres RLS policies filter on this GUC.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT_SQL = "select set_config('app.current_tenant', ?, false)";

    private final MultiTenancyProperties properties;

    public TenantAwareDataSource(DataSource target, MultiTenancyProperties properties) {
        super(target);
        this.properties = properties;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        applyTenant(connection, resolveTenantValue());
        return wrap(connection);
    }

    private String resolveTenantValue() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant != null) {
            return tenant.toString();
        }
        if (properties.isFallbackToDefault() && properties.getDefaultTenantId() != null) {
            return properties.getDefaultTenantId().toString();
        }
        return "";
    }

    private static void applyTenant(Connection connection, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT_SQL)) {
            ps.setString(1, value);
            ps.execute();
        }
    }

    private static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class[]{Connection.class},
                new ResetOnCloseHandler(connection));
    }

    private record ResetOnCloseHandler(Connection delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                try {
                    applyTenant(delegate, "");
                } catch (SQLException ignored) {
                    // best-effort reset; still close below
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
