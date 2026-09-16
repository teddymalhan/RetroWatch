package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builders for Gemini {@code generationConfig.responseSchema} documents.
 *
 * <p>The REST API takes the same OpenAPI-subset schema the Vertex SDK did, but as plain
 * JSON with upper-case type names instead of protobuf {@code Schema} messages. Keeping
 * the construction here means the three services that ask Gemini for structured output
 * share one vocabulary.
 */
public final class GeminiSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeminiSchemas() {
    }

    public static Builder object() {
        return new Builder();
    }

    public static ObjectNode string() {
        return MAPPER.createObjectNode().put("type", "STRING");
    }

    public static ObjectNode integer() {
        return MAPPER.createObjectNode().put("type", "INTEGER");
    }

    public static ObjectNode arrayOf(ObjectNode items) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "ARRAY");
        node.set("items", items);
        return node;
    }

    public static ObjectNode stringArray() {
        return arrayOf(string());
    }

    /**
     * Builds an {@code OBJECT} schema from named properties plus a required list.
     */
    public static final class Builder {

        private final ObjectNode schema = MAPPER.createObjectNode().put("type", "OBJECT");
        private final ObjectNode properties = MAPPER.createObjectNode();
        private final ArrayNode required = MAPPER.createArrayNode();

        private Builder() {
            schema.set("properties", properties);
        }

        public Builder prop(String name, ObjectNode propertySchema) {
            properties.set(name, propertySchema);
            return this;
        }

        public Builder require(String... names) {
            for (String name : names) {
                required.add(name);
            }
            return this;
        }

        public ObjectNode build() {
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
            return schema;
        }
    }
}
