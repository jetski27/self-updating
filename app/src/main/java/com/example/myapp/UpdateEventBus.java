package com.example.myapp;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UpdateEventBus {

    private final BroadcastProcessor<String> processor = BroadcastProcessor.create();

    public void emit(String event) {
        processor.onNext(event);
    }

    public Multi<String> stream() {
        return processor;
    }

    @PreDestroy
    void shutdown() {
        processor.onComplete();
    }
}
