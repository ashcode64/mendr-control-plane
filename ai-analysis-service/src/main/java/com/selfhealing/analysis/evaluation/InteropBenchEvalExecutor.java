package com.selfhealing.analysis.evaluation;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eval-only MendrScript subset including nested {@code move} for Mode A structural fixtures.
 */
public final class InteropBenchEvalExecutor {

    private InteropBenchEvalExecutor() {}

    @SuppressWarnings("unchecked")
    public static Object execute(Map<String, Object> program, Object root) {
        Object cur = deepCopy(root);
        Object opsObj = program.get("ops");
        if (!(opsObj instanceof List<?> ops)) {
            return cur;
        }
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> op)) continue;
            String name = String.valueOf(op.get("op"));
            cur = switch (name) {
                case "rename" -> rename(cur, str(op.get("from")), str(op.get("to")));
                case "move" -> move(cur, str(op.get("from")), str(op.get("to")));
                case "scale" -> mutateNumber(cur, str(op.get("path")),
                        v -> (v * num(op.get("numerator"), 1)) / num(op.get("denominator"), 1));
                case "arith" -> mutateNumber(cur, str(op.get("path")),
                        v -> arith(v, str(op.get("operator")), num(op.get("operand"), 0)));
                case "reformat_date" -> reformatDate(cur, str(op.get("path")),
                        str(op.get("sourceFormat")), str(op.get("targetFormat")));
                case "coerce" -> coerce(cur, str(op.get("path")), str(op.get("targetType")));
                case "default" -> defaultOp(cur, str(op.get("path")), op.get("value"));
                default -> cur;
            };
        }
        return cur;
    }

    private static Object rename(Object root, String from, String to) {
        Object v = getAt(root, from);
        if (v == null && !pointerExists(root, from)) return root;
        Object without = removeAt(root, from);
        return setAt(without, to, v);
    }

    private static Object move(Object root, String from, String to) {
        return rename(root, from, to);
    }

    private interface NumFn { double apply(double v); }

    private static Object mutateNumber(Object root, String path, NumFn fn) {
        Object cur = getAt(root, path);
        if (!(cur instanceof Number n)) return root;
        return setAt(root, path, fn.apply(n.doubleValue()));
    }

    private static double arith(double v, String op, double operand) {
        return switch (op) {
            case "+" -> v + operand;
            case "-" -> v - operand;
            case "*" -> v * operand;
            case "/" -> operand == 0 ? v : v / operand;
            default -> v;
        };
    }

    private static Object reformatDate(Object root, String path, String src, String tgt) {
        Object v = getAt(root, path);
        if (v == null) return root;
        long epochMs = toEpochMs(v, src);
        return setAt(root, path, fromEpochMs(epochMs, tgt));
    }

    private static long toEpochMs(Object v, String format) {
        return switch (format) {
            case "epoch_s" -> ((Number) v).longValue() * 1000L;
            case "epoch_ms" -> ((Number) v).longValue();
            default -> Instant.parse(String.valueOf(v)).toEpochMilli();
        };
    }

    private static Object fromEpochMs(long epochMs, String format) {
        return switch (format) {
            case "epoch_s" -> epochMs / 1000L;
            case "epoch_ms" -> epochMs;
            default -> DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs));
        };
    }

    private static Object coerce(Object root, String path, String targetType) {
        Object v = getAt(root, path);
        if (v == null) return root;
        Object coerced = switch (targetType) {
            case "integer", "int" -> Integer.parseInt(String.valueOf(v));
            case "number", "double", "float" -> Double.parseDouble(String.valueOf(v));
            case "string" -> String.valueOf(v);
            case "boolean" -> Boolean.parseBoolean(String.valueOf(v));
            default -> v;
        };
        return setAt(root, path, coerced);
    }

    private static Object defaultOp(Object root, String path, Object value) {
        if (pointerExists(root, path)) return root;
        return setAt(root, path, value);
    }

    private static List<String> parts(String pointer) {
        if (pointer == null || pointer.isBlank() || "/".equals(pointer)) return List.of();
        String p = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        if (p.isEmpty()) return List.of();
        String[] raw = p.split("/");
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            out.add(s.replace("~1", "/").replace("~0", "~"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object getAt(Object root, String pointer) {
        Object cur = root;
        for (String part : parts(pointer)) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(part);
            } else if (cur instanceof List<?> list) {
                int idx = Integer.parseInt(part);
                if (idx < 0 || idx >= list.size()) return null;
                cur = list.get(idx);
            } else {
                return null;
            }
        }
        return cur;
    }

    private static boolean pointerExists(Object root, String pointer) {
        Object cur = root;
        for (String part : parts(pointer)) {
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(part)) return false;
                cur = m.get(part);
            } else if (cur instanceof List<?> list) {
                int idx = Integer.parseInt(part);
                if (idx < 0 || idx >= list.size()) return false;
                cur = list.get(idx);
            } else {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Object setAt(Object root, String pointer, Object value) {
        List<String> segs = parts(pointer);
        if (segs.isEmpty()) return value;
        Object cur = root;
        if (!(cur instanceof Map) && !(cur instanceof List)) {
            cur = new LinkedHashMap<String, Object>();
            root = cur;
        }
        for (int i = 0; i < segs.size() - 1; i++) {
            String part = segs.get(i);
            Object next;
            if (cur instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                next = mm.get(part);
                if (!(next instanceof Map) && !(next instanceof List)) {
                    next = new LinkedHashMap<String, Object>();
                    mm.put(part, next);
                }
                cur = next;
            } else {
                return root;
            }
        }
        String last = segs.get(segs.size() - 1);
        if (cur instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).put(last, value);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Object removeAt(Object root, String pointer) {
        List<String> segs = parts(pointer);
        if (segs.isEmpty()) return root;
        List<Object> stack = new ArrayList<>();
        Object cur = root;
        stack.add(cur);
        for (int i = 0; i < segs.size() - 1; i++) {
            if (!(cur instanceof Map<?, ?> m)) return root;
            cur = m.get(segs.get(i));
            stack.add(cur);
        }
        String last = segs.get(segs.size() - 1);
        if (cur instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).remove(last);
        } else if (cur instanceof List<?> list) {
            int idx = Integer.parseInt(last);
            if (idx >= 0 && idx < list.size()) {
                ((List<Object>) list).remove(idx);
            }
        }
        // Prune empty ancestor maps so un-nest matches golden flat shapes.
        for (int i = segs.size() - 2; i >= 0; i--) {
            Object node = stack.get(i + 1);
            Object parent = stack.get(i);
            if (node instanceof Map<?, ?> child && child.isEmpty() && parent instanceof Map<?, ?> pm) {
                ((Map<String, Object>) pm).remove(segs.get(i));
            } else {
                break;
            }
        }
        return root;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? dflt : Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return dflt;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), deepCopy(v)));
            return copy;
        }
        if (o instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object e : list) copy.add(deepCopy(e));
            return copy;
        }
        return o;
    }

    public static boolean deepEqualsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number na && b instanceof Number nb) {
            return Math.abs(na.doubleValue() - nb.doubleValue()) < 1e-4;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) return false;
            for (Object k : ma.keySet()) {
                if (!mb.containsKey(k)) return false;
                if (!deepEqualsLoose(ma.get(k), mb.get(k))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    public static double fieldF1(Map<String, Object> predicted, Map<String, Object> golden) {
        if (golden == null || golden.isEmpty()) return predicted == null || predicted.isEmpty() ? 1.0 : 0.0;
        if (predicted == null) return 0.0;
        int tp = 0;
        for (String k : golden.keySet()) {
            if (predicted.containsKey(k)) tp++;
        }
        int fp = 0;
        for (String k : predicted.keySet()) {
            if (!golden.containsKey(k)) fp++;
        }
        int fn = golden.size() - tp;
        double prec = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
        double rec = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
        return prec + rec == 0 ? 0.0 : 2 * prec * rec / (prec + rec);
    }

    public static double valueAccuracy(Map<String, Object> predicted, Map<String, Object> golden) {
        if (golden == null || golden.isEmpty()) return 1.0;
        if (predicted == null) return 0.0;
        int ok = 0;
        for (Map.Entry<String, Object> e : golden.entrySet()) {
            if (deepEqualsLoose(predicted.get(e.getKey()), e.getValue())) ok++;
        }
        return (double) ok / golden.size();
    }
}
