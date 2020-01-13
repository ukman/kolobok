package org.kolobok.annotation.processor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Descriptor for Spring repository class method (e.g. 'findByFirstNameAndLastName').
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
public class RepoMethod {

    public RepoMethod() {
    }

    public RepoMethod(Type type, Part[] parts) {
        this.type = type;
        this.parts = parts;
    }

    /**
     * Type of find method
     */
    public enum Type {

        COUNT("countBy"),
        FIND("findBy", "");

        /**
         * Possible prefix for this type of repository method.
         */
        private final String[] prefixes;

        Type(String... prefixes) {
            this.prefixes = prefixes;
        }

        public String getDefaultPrefix() {
            return prefixes[0];
        }

        public String getGeneratedMethodPrefix() {
            return prefixes[prefixes.length - 1];
        }

        public String[] getPrefixes() {
            return prefixes;
        }
    }

//    public enum PostfixOperation {
//        IN, BETWEEN, EQUALS, IS_NULL, IS_NOT_NULL, GREATER, LESS, GREATER_EQUALS, LESS_EQUALS
//    }

    /**
     * Type of operation between parts (And/Or)
     */
    public enum Operation {
        Or, And;
    }

    /**
     * Part of find method (field with operation before field)
     */
    public static class Part {

        /**
         * Operation before field (And/Or)
         */
        private Operation preOperation;

        /**
         * Field or field with operation ('lastName', 'lastNameIsNull')
         */
        private String fullExpression;

        public Part(Operation preOperation, String fullExpression) {
            this.preOperation = preOperation;
            this.fullExpression = fullExpression;
        }

        public Part(String fullExpression) {
            this.fullExpression = fullExpression;
        }

        public Operation getPreOperation() {
            return preOperation;
        }

        public String getFullExpression() {
            return fullExpression;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Part part = (Part) o;
            return preOperation == part.preOperation &&
                    Objects.equals(fullExpression, part.fullExpression);
        }

        @Override
        public int hashCode() {
            return Objects.hash(preOperation, fullExpression);
        }

        @Override
        public String toString() {
            return "Part{" +
                    "preOperation=" + preOperation +
                    ", fullExpression='" + fullExpression + '\'' +
                    '}';
        }
    }

    private Type type;
    private Part[] parts;

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Part[] getParts() {
        return parts;
    }

    public void setParts(Part[] parts) {
        this.parts = parts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RepoMethod that = (RepoMethod) o;
        return type == that.type &&
                Arrays.equals(parts, that.parts);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type);
        result = 31 * result + Arrays.hashCode(parts);
        return result;
    }

    @Override
    public String toString() {
        return "RepoMethod{" +
                "type=" + type +
                ", parts=" + Arrays.toString(parts) +
                '}';
    }
}
