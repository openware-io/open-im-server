package io.openware.infrastructure.currency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 币种上下文 holder / 解析器：缺省 USD、显式写入、清理、并发不串味。 */
class CurrencyResolverTest {

    @AfterEach
    void tearDown() {
        CurrencyContextHolder.clear();
    }

    @Test
    void defaultsToUsdWithoutContext() {
        assertThat(CurrencyResolver.current()).isEqualTo(Currency.USD);
        assertThat(CurrencyResolver.currentCode()).isEqualTo("USD");
        assertThat(CurrencyResolver.currentSymbol()).isEqualTo("$");
        assertThat(CurrencyContextHolder.getOrNull()).isNull();
    }

    @Test
    void readsExplicitContextCurrency() {
        CurrencyContextHolder.set(Currency.CNY);
        assertThat(CurrencyResolver.current()).isEqualTo(Currency.CNY);
        assertThat(CurrencyResolver.currentCode()).isEqualTo("CNY");
        assertThat(CurrencyResolver.format(18800L)).isEqualTo("¥188.00");
    }

    @Test
    void clearRestoresUsdDefault() {
        CurrencyContextHolder.set(Currency.CNY);
        CurrencyContextHolder.clear();
        assertThat(CurrencyResolver.current()).isEqualTo(Currency.USD);
    }

    @Test
    void settingNullDoesNotLeakPreviousCurrency() {
        CurrencyContextHolder.set(Currency.CNY);
        CurrencyContextHolder.set(null);
        assertThat(CurrencyResolver.current()).isEqualTo(Currency.USD);
    }

    /** 线程池复用下不同线程的币种不得互相串味（过滤器 finally clear 的核心保障）。 */
    @Test
    void concurrentRequestsDoNotLeakCurrencyAcrossThreads() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Currency currency = i % 2 == 0 ? Currency.CNY : Currency.USD;
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    CurrencyContextHolder.set(currency);
                    // 多次读取，确保读到的始终是本线程写入的值
                    for (int round = 0; round < 200; round++) {
                        if (CurrencyResolver.current() != currency) {
                            return "LEAK:" + currency + "->" + CurrencyResolver.current();
                        }
                        Thread.yield();
                    }
                    CurrencyContextHolder.clear();
                    return CurrencyResolver.current() == Currency.USD ? "OK" : "NOT_CLEARED";
                });
            }
            List<Future<String>> results = pool.invokeAll(tasks);
            for (Future<String> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
