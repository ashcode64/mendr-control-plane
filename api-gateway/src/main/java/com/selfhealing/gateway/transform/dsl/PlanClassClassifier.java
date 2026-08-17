package com.selfhealing.gateway.transform.dsl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compile-time execution class for a MendrScript program.
 * Rank: PASSTHROUGH &lt; PREFILTERABLE &lt; FORWARD_ONLY &lt; BOUNDED_WINDOW &lt; UNBOUNDED.
 *
 * <p>Classification starts from {@link ProgramSignature} (opcode list +
 * reads/writes). Ops are walked only to refine {@code move}/{@code copy}
 * same-parent → {@code BOUNDED_WINDOW} vs {@code UNBOUNDED}, and {@code default}
 * trigger (ABSENT blocks prefilter).
 */
public final class PlanClassClassifier {

    public static final String PASSTHROUGH = "PASSTHROUGH";
    public static final String PREFILTERABLE = "PREFILTERABLE";
    public static final String FORWARD_ONLY = "FORWARD_ONLY";
    public static final String BOUNDED_WINDOW = "BOUNDED_WINDOW";
    public static final String UNBOUNDED = "UNBOUNDED";

    public record Classification(String planClass,
                                 List<String> prefilterLiterals,
                                 List<String> writePointers,
                                 String maxWindowDepth,
                                 boolean prefilterable) {}

    private PlanClassClassifier() {}

    public static Classification classify(MendrProgram program) {
        ProgramSignature sig = ProgramSignature.of(program);
        State s = new State();
        if (sig.opCount() == 0) {
            return s.finish();
        }
        for (String p : sig.writes()) s.addPointer(p);
        for (String p : sig.reads()) s.addPointer(p);
        for (String opcode : sig.opcodes()) {
            applyOpcode(opcode, s);
        }
        refine(program == null ? List.of() : program.ops(), s);
        return s.finish();
    }

    public static Classification classifyOps(List<Op> ops) {
        return classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA, ops == null ? List.of() : ops));
    }

    /** Classify a snapshot-shaped ops list ({@code {op, ...}} maps). */
    @SuppressWarnings("unchecked")
    public static Classification classifySnapshotOps(List<java.util.Map<String, Object>> ops) {
        State s = new State();
        if (ops != null) {
            for (java.util.Map<String, Object> op : ops) {
                walkMap(op, s);
            }
        }
        return s.finish();
    }

    public static Classification merge(Classification a, Classification b) {
        if (a == null) return b;
        if (b == null) return a;
        State s = new State();
        s.absorb(a);
        s.absorb(b);
        return s.finish();
    }

    private static void walkMap(java.util.Map<String, Object> op, State s) {
        if (op == null) return;
        String kind = str(op.get("op"));
        switch (kind) {
            case "conditional" -> {
                Object then = op.get("then");
                Object otherwise = op.get("otherwise");
                if (then instanceof List<?> list) {
                    for (Object c : list) {
                        if (c instanceof java.util.Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            var cm = (java.util.Map<String, Object>) m;
                            walkMap(cm, s);
                        }
                    }
                }
                if (otherwise instanceof List<?> list) {
                    for (Object c : list) {
                        if (c instanceof java.util.Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            var cm = (java.util.Map<String, Object>) m;
                            walkMap(cm, s);
                        }
                    }
                }
            }
            case "move", "copy" -> {
                bumpMoveOrCopy(s, str(op.get("from")), str(op.get("to")));
                s.addPointer(str(op.get("from")));
                s.addPointer(str(op.get("to")));
            }
            case "wrap", "unwrap" -> {
                s.bump(FORWARD_ONLY);
                s.prefilterable = false;
            }
            case "strip_unknown" -> {
                s.bump(FORWARD_ONLY);
                s.prefilterable = false;
                s.addPointer(str(op.get("path")));
            }
            case "default" -> {
                s.bump(FORWARD_ONLY);
                String on = str(op.get("on")).toUpperCase();
                if (!"NULL".equals(on)) {
                    s.prefilterable = false;
                }
                s.addPointer(str(op.get("path")));
            }
            case "rename" -> {
                s.bump(FORWARD_ONLY);
                s.addPointer(str(op.get("from")));
                s.addPointer(str(op.get("to")));
            }
            default -> {
                if (!kind.isEmpty()) {
                    s.bump(FORWARD_ONLY);
                    s.addPointer(str(op.get("path")));
                    s.addPointer(str(op.get("from")));
                    s.addPointer(str(op.get("to")));
                }
            }
        }
    }

    private static void applyOpcode(String opcode, State s) {
        if (opcode == null || opcode.isEmpty()) return;
        switch (opcode) {
            case "conditional" -> { /* union of children via refine */ }
            case "move", "copy" -> s.prefilterable = false;
            case "wrap", "unwrap", "strip_unknown" -> {
                s.bump(FORWARD_ONLY);
                s.prefilterable = false;
            }
            default -> s.bump(FORWARD_ONLY);
        }
    }

    private static void refine(List<Op> ops, State s) {
        boolean[] sawMove = {false};
        boolean[] allSame = {true};
        boolean[] defaultBlocks = {false};
        walkRefine(ops, sawMove, allSame, defaultBlocks);
        if (defaultBlocks[0]) {
            s.prefilterable = false;
        }
        if (sawMove[0] && s.rank < rankOf(UNBOUNDED)) {
            if (allSame[0]) {
                s.bump(BOUNDED_WINDOW);
            } else {
                s.bump(UNBOUNDED);
            }
            s.prefilterable = false;
        }
    }

    private static void walkRefine(List<Op> ops, boolean[] sawMove, boolean[] allSame,
                                   boolean[] defaultBlocks) {
        if (ops == null) return;
        for (Op op : ops) {
            if (op instanceof Op.Move m) {
                sawMove[0] = true;
                if (!sameParent(m.from(), m.to())) allSame[0] = false;
            } else if (op instanceof Op.Copy c) {
                sawMove[0] = true;
                if (!sameParent(c.from(), c.to())) allSame[0] = false;
            } else if (op instanceof Op.Default d) {
                if (d.on() != Op.Trigger.NULL) defaultBlocks[0] = true;
            } else if (op instanceof Op.Conditional cond) {
                walkRefine(cond.children(), sawMove, allSame, defaultBlocks);
            }
        }
    }

    private static void bumpMoveOrCopy(State s, String from, String to) {
        s.prefilterable = false;
        if (sameParent(from, to)) {
            s.bump(BOUNDED_WINDOW);
        } else {
            s.bump(UNBOUNDED);
        }
    }

    static boolean sameParent(String a, String b) {
        return parentOf(a).equals(parentOf(b));
    }

    static String parentOf(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) return "";
        int i = pointer.lastIndexOf('/');
        return i <= 0 ? "" : pointer.substring(0, i);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static final class State {
        int rank;
        boolean prefilterable = true;
        final Set<String> literals = new LinkedHashSet<>();
        final Set<String> pointers = new LinkedHashSet<>();
        int maxDepth;

        void bump(String planClass) {
            int r = rankOf(planClass);
            if (r > rank) rank = r;
        }

        void addPointer(String pointer) {
            if (pointer == null || pointer.isEmpty()) return;
            pointers.add(pointer);
            String lit = lastSegment(pointer);
            if (lit != null && !lit.isEmpty()) literals.add(lit);
            int d = depth(pointer);
            if (d > maxDepth) maxDepth = d;
        }

        void absorb(Classification c) {
            if (c == null || PASSTHROUGH.equals(c.planClass())) {
                return;
            }
            bump(c.planClass());
            if (!c.prefilterable()) prefilterable = false;
            literals.addAll(c.prefilterLiterals());
            pointers.addAll(c.writePointers());
            if ("UNBOUNDED".equals(c.maxWindowDepth())) {
                maxDepth = Integer.MAX_VALUE;
            } else {
                try {
                    int d = Integer.parseInt(c.maxWindowDepth());
                    if (d > maxDepth) maxDepth = d;
                } catch (NumberFormatException ignored) {
                    maxDepth = Integer.MAX_VALUE;
                }
            }
        }

        Classification finish() {
            String planClass = classOf(rank);
            if (rank == 0) {
                return new Classification(PASSTHROUGH, List.of(), List.of(), "0", false);
            }
            if (rank == rankOf(FORWARD_ONLY) && prefilterable && !literals.isEmpty()) {
                planClass = PREFILTERABLE;
            }
            String depth = (rank >= rankOf(UNBOUNDED) || maxDepth == Integer.MAX_VALUE)
                    ? UNBOUNDED : Integer.toString(maxDepth);
            return new Classification(planClass,
                    List.copyOf(literals),
                    List.copyOf(pointers),
                    depth,
                    PREFILTERABLE.equals(planClass));
        }
    }

    private static int rankOf(String c) {
        return switch (c) {
            case PASSTHROUGH -> 0;
            case PREFILTERABLE -> 1;
            case FORWARD_ONLY -> 2;
            case BOUNDED_WINDOW -> 3;
            default -> 4;
        };
    }

    private static String classOf(int rank) {
        return switch (rank) {
            case 0 -> PASSTHROUGH;
            case 1 -> PREFILTERABLE;
            case 2 -> FORWARD_ONLY;
            case 3 -> BOUNDED_WINDOW;
            default -> UNBOUNDED;
        };
    }

    static String lastSegment(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) return "";
        int i = pointer.lastIndexOf('/');
        String seg = i >= 0 ? pointer.substring(i + 1) : pointer;
        return seg.replace("~1", "/").replace("~0", "~");
    }

    static int depth(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) return 0;
        int n = 0;
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '/') n++;
        }
        return n;
    }
}
