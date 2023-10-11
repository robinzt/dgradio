package com.skywing.dgradio.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MsgJsonMapper {
    private static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static ObjectMapper getObjectMapper() {
        return mapper;
    }
}
