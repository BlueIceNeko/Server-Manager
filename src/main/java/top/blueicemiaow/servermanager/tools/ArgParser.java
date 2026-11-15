package top.blueicemiaow.servermanager.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small GNU-style command line parser. It understands combined short options
 * ({@code -la}), short options with a value ({@code -t 202601011200}), long flags
 * ({@code --help}) and the {@code --} end-of-options marker.
 */
public final class ArgParser {
    private final Set<Character> flags = new HashSet<>();
    private final Map<Character, String> values = new HashMap<>();
    private final Set<String> longFlags = new HashSet<>();
    private final List<String> operands = new ArrayList<>();
    private final List<String> unknown = new ArrayList<>();

    public ArgParser(List<String> args, String valueOptions, Set<String> acceptedLongFlags) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);

            if (arg.equals("--")) {
                operands.addAll(args.subList(i + 1, args.size()));
                break;
            }

            if (arg.startsWith("--") && arg.length() > 2) {
                String name = arg.substring(2);
                String value = null;
                int equals = name.indexOf('=');
                if (equals >= 0) {
                    value = name.substring(equals + 1);
                    name = name.substring(0, equals);
                }

                if (acceptedLongFlags.contains(name) && value == null) {
                    longFlags.add(name);
                } else {
                    unknown.add(arg);
                }
                continue;
            }

            if (arg.length() > 1 && arg.charAt(0) == '-') {
                for (int j = 1; j < arg.length(); j++) {
                    char option = arg.charAt(j);

                    if (valueOptions.indexOf(option) >= 0) {
                        String value;
                        if (j + 1 < arg.length()) {
                            value = arg.substring(j + 1);
                        } else if (i + 1 < args.size()) {
                            value = args.get(++i);
                        } else {
                            unknown.add("-" + option);
                            value = "";
                        }
                        values.put(option, value);
                        break;
                    }

                    flags.add(option);
                }
                continue;
            }

            operands.add(arg);
        }
    }

    public boolean has(char option) {
        return flags.contains(option);
    }

    public boolean hasLong(String option) {
        return longFlags.contains(option);
    }

    public String value(char option) {
        return values.get(option);
    }

    public List<String> operands() {
        return operands;
    }

    public List<String> unknown() {
        return unknown;
    }

    /**
     * Combined short flags, in a stable order, for validating which options a command accepts.
     */
    public String flagsOnly(String accepted) {
        StringBuilder invalid = new StringBuilder();
        for (char option : flags) {
            if (accepted.indexOf(option) < 0) {
                invalid.append(invalid.isEmpty() ? "" : ", ").append('-').append(option);
            }
        }
        return invalid.toString();
    }

    public String unknownOptions() {
        return String.join(", ", unknown);
    }

    /**
     * Options that consumed a value, for validating which value-taking options a command accepts.
     */
    public String valueOptionsOnly(String accepted) {
        StringBuilder invalid = new StringBuilder();
        for (char option : values.keySet()) {
            if (accepted.indexOf(option) < 0) {
                invalid.append(invalid.isEmpty() ? "" : ", ").append('-').append(option);
            }
        }
        return invalid.toString();
    }
}
