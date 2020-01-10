package org.kolobok.annotation.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RepoMethodUtil {

    public RepoMethod parseMethodName(String methodName) {
        RepoMethod.Type type = null;
        List<RepoMethod.Part> parts = new ArrayList();
        int idx = 0;
        topFor:
        for(RepoMethod.Type t : RepoMethod.Type.values()) {
            for(String prefix : t.getPrefixes()) {
                if(methodName.startsWith(prefix)) {
                    if(!prefix.isEmpty()) {
                        if(isFirstUpperSymbolOfProperty(methodName.charAt(prefix.length()))) {
                            type = t;
                            idx = prefix.length();
                            break topFor;
                        }
                    } else {
                        type = t;
                        idx = prefix.length();
                        break topFor;
                    }
                }
            }
        }
        String postFix = methodName.substring(idx, idx + 1).toLowerCase() + methodName.substring(idx + 1);
        idx = 0;

        Matcher m = SEPARATOR_PATTERN.matcher(postFix);
        RepoMethod.Operation op = null;
        while(m.find()) {
            int start = m.start();
            if(start > 0) {
                int end = m.end();
                if(end < postFix.length()) {
                    char postC = postFix.charAt(end);
                    if(Character.toUpperCase(postC) == postC) {
                        String fieldName = postFix.substring(idx, idx + 1).toLowerCase()  + postFix.substring(idx + 1, start);
                        parts.add(new RepoMethod.Part(op, fieldName));
                        op = RepoMethod.Operation.valueOf(m.group());
                        idx = end;
                    }
                }
            }
        }
        String fieldName = postFix.substring(idx, idx + 1).toLowerCase()  + postFix.substring(idx + 1, postFix.length());
        parts.add(new RepoMethod.Part(op, fieldName));
        return new RepoMethod(type, parts.toArray(new RepoMethod.Part[parts.size()]));
    }

    public static final String SEPARATOR_REGEXP = "(" + RepoMethod.Operation.And + "|" + RepoMethod.Operation.Or + ")";
    public static final Pattern SEPARATOR_PATTERN = Pattern.compile(SEPARATOR_REGEXP);

    private boolean isFirstUpperSymbolOfProperty(char c) {
        return (c == Character.toUpperCase(c));
    }

    /**
     * Generates repository find method by parts
     * @param newParts part of method to be generated
     * @return method name
     */
    public String generateMethodName(List<RepoMethod.Part> newParts) {
        StringBuilder sb = new StringBuilder();
        for (RepoMethod.Part newPart : newParts) {
            if(sb.length() > 0) {
                sb.append(newPart.getPreOperation());
                sb.append(firstLetterToUpperCase(newPart.getFullExpression()));
            } else {
                sb.append(firstLetterToLowerCase(newPart.getFullExpression()));
            }
        }
        return sb.toString();
    }


    /**
     * Converts first symbol of a string to lower case
     * @param s string to be converted
     * @return converted string
     */
    public String firstLetterToLowerCase(String s) {
        return s.length() == 0 ? s : s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    /**
     * Converts first symbol of a string to upper case
     * @param s string to be converted
     * @return converted string
     */
    public String firstLetterToUpperCase(String s) {
        return s.length() == 0 ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

}
