package io.openware.platform.customer.application;

/** Customer 域事实事件的出站端口，由基础设施适配器在当前业务事务内持久化。 */
@FunctionalInterface
public interface CustomerEventOutbox {
    void append(CustomerFactEvent event);

    static CustomerEventOutbox disabled() {
        return event -> { };
    }
}
