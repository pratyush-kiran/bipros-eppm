package com.bipros.hds.application.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("test")
@ConditionalOnMissingBean(LlmGateway.class)
public class StubLlmGateway implements LlmGateway {
    @Override public String completeStructured(List<ChatMessage> messages, String fmt) {
        return "{\"intent\":\"specific\",\"is_compound\":false,\"sub_questions\":[]," +
            "\"search_queries\":[\"stub\"],\"passed\":true,\"sufficient\":true," +
            "\"follow_up_queries\":[],\"issues\":[]}";
    }
    @Override public String completeStreaming(List<ChatMessage> messages, StreamCallback cb) {
        String t = "Per the provided chunks [c1], answer is X.";
        if (cb != null) cb.onToken(t);
        return t;
    }
}
