package com.backtesting.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;

/**
 * JSON ↔ String 변환기 베이스. 제네릭 T 유지를 위해 AttributeConverter는
 * 타입별 서브클래스로 구현한다 (JPA는 no-arg 생성자 요구).
 *
 * 서브클래스 예:
 *   public class MyTypeJsonConverter extends JsonConverter<MyType> {
 *       public MyTypeJsonConverter() { super(new TypeReference<MyType>() {}); }
 *   }
 */
public abstract class JsonConverter<T> implements AttributeConverter<T, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final TypeReference<T> type;

    protected JsonConverter(TypeReference<T> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(T attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialize failed: " + e.getMessage(), e);
        }
    }

    @Override
    public T convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON deserialize failed: " + e.getMessage(), e);
        }
    }

    public static ObjectMapper mapper() { return MAPPER; }
}
