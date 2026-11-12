package com.dws.trade.query;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

/**
 * Stores a date as a plain ISO string such as "2027-05-20". MongoDB's default turns it into an
 * instant at midnight in the JVM's zone, which reads back a day off if the zone ever changes.
 */
@Configuration
public class MongoConfig {

    @Bean
    MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(LocalDateToString.INSTANCE, StringToLocalDate.INSTANCE));
    }

    @WritingConverter
    enum LocalDateToString implements Converter<LocalDate, String> {
        INSTANCE;

        @Override
        public String convert(LocalDate source) {
            return source.toString();
        }
    }

    @ReadingConverter
    enum StringToLocalDate implements Converter<String, LocalDate> {
        INSTANCE;

        @Override
        public LocalDate convert(String source) {
            return LocalDate.parse(source);
        }
    }
}
