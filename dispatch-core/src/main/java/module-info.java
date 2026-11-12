/** The queue engine. JDK and SLF4J only. */
module dev.dispatch.core {
    requires org.slf4j;

    exports dev.dispatch.core.demo;
    exports dev.dispatch.core.engine;
    exports dev.dispatch.core.handler;
    exports dev.dispatch.core.job;
    exports dev.dispatch.core.retry;
    exports dev.dispatch.core.store;
}
