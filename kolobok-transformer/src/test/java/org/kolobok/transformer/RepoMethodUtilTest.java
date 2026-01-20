package org.kolobok.transformer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for repo method parser.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
public class RepoMethodUtilTest {

    @Test
    public void testParser() {
        RepoMethodUtil util = new RepoMethodUtil();
        for (TestParser test : TESTS) {
            RepoMethod method = util.parseMethodName(test.getMethodName());
            assertThat(method).isEqualTo(test.getRepoMethod());
        }
    }

    public static class TestParser {
        private String methodName;

        private RepoMethod repoMethod;

        public TestParser(String methodName, RepoMethod repoMethod) {
            this.methodName = methodName;
            this.repoMethod = repoMethod;
        }

        public String getMethodName() {
            return methodName;
        }

        public RepoMethod getRepoMethod() {
            return repoMethod;
        }
    }

    public static final TestParser[] TESTS = new TestParser[]{
            new TestParser("findByName", new RepoMethod(RepoMethod.Type.FIND, new RepoMethod.Part[]{new RepoMethod.Part("name")})),
            new TestParser("name", new RepoMethod(RepoMethod.Type.FIND, new RepoMethod.Part[]{new RepoMethod.Part("name")})),
            new TestParser("countByName", new RepoMethod(RepoMethod.Type.COUNT, new RepoMethod.Part[]{new RepoMethod.Part("name")})),
            new TestParser("findByFirstNameAndLastName", new RepoMethod(RepoMethod.Type.FIND,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("firstName"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"lastName")
            })),
            new TestParser("findByFirstNameOrLastName", new RepoMethod(RepoMethod.Type.FIND,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("firstName"),
                            new RepoMethod.Part(RepoMethod.Operation.Or,"lastName")
            })),
            new TestParser("countByFirstNameOrLastName", new RepoMethod(RepoMethod.Type.COUNT,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("firstName"),
                            new RepoMethod.Part(RepoMethod.Operation.Or,"lastName")
            })),
            new TestParser("findByAAndBAndC", new RepoMethod(RepoMethod.Type.FIND,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("a"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"b"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"c")
            })),
            new TestParser("findByAAndBAndCAndAndroid", new RepoMethod(RepoMethod.Type.FIND,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("a"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"b"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"c"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"android")
            })),
            new TestParser("countByAAndBAndCAndAndroid", new RepoMethod(RepoMethod.Type.COUNT,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("a"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"b"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"c"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"android")
            })),
            new TestParser("aAndBAndCAndAndroid", new RepoMethod(RepoMethod.Type.FIND,
                    new RepoMethod.Part[]{
                            new RepoMethod.Part("a"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"b"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"c"),
                            new RepoMethod.Part(RepoMethod.Operation.And,"android")
            })),
    };

    public static class GeneratedMethod {

        private String methodName;

        private List<RepoMethod.Part> parts;

        public GeneratedMethod(String methodName, List<RepoMethod.Part> parts) {
            this.methodName = methodName;
            this.parts = parts;
        }

        public String getMethodName() {
            return methodName;
        }

        public List<RepoMethod.Part> getParts() {
            return parts;
        }
    }

    public static final GeneratedMethod[] TEST_GENERATE_METHOD = new GeneratedMethod[]{
            new GeneratedMethod("name", Arrays.asList(new RepoMethod.Part("name"))),
            new GeneratedMethod("name", Arrays.asList(new RepoMethod.Part(RepoMethod.Operation.And,"name"))),
            new GeneratedMethod("name", Arrays.asList(new RepoMethod.Part(RepoMethod.Operation.Or,"name"))),
            new GeneratedMethod("firstName", Arrays.asList(new RepoMethod.Part(RepoMethod.Operation.Or,"firstName"))),
            new GeneratedMethod("firstNameAndLastName", Arrays.asList(
                    new RepoMethod.Part(RepoMethod.Operation.And,"firstName"),
                    new RepoMethod.Part(RepoMethod.Operation.And,"lastName")
            )),
            new GeneratedMethod("firstNameOrLastName", Arrays.asList(
                    new RepoMethod.Part(RepoMethod.Operation.And,"firstName"),
                    new RepoMethod.Part(RepoMethod.Operation.Or,"lastName")
            )),
    };

    @Test
    public void testGenerateMethod() {
        RepoMethodUtil util = new RepoMethodUtil();
        for (GeneratedMethod generatedMethod : TEST_GENERATE_METHOD) {
            String generatedMethodName = util.generateMethodName(generatedMethod.getParts());
            assertThat(generatedMethodName).isEqualTo(generatedMethod.getMethodName());
        }
    }

}
