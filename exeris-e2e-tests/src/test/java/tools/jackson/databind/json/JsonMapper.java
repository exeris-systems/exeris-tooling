package tools.jackson.databind.json;

import tools.jackson.databind.ObjectMapper;

/**
 * Test stub for the Jackson 3.x {@code JsonMapper}. Generated saga code
 * obtains an {@link ObjectMapper} via {@code JsonMapper.builder().build()}.
 */
public final class JsonMapper {

    private JsonMapper() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        public ObjectMapper build() {
            return new ObjectMapper();
        }
    }
}
