package com.geneea.examples

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.junit.Rule
import org.rnorth.ducttape.unreliables.Unreliables
import com.geneea.celery.Celery
import com.geneea.celery.CeleryWorker
import com.geneea.celery.WorkerException
import com.geneea.celery.backends.rabbit.RabbitBackend
import com.geneea.celery.brokers.rabbit.RabbitBroker
import com.geneea.celery.examples.BadTaskProxy
import com.geneea.celery.examples.TestTask
import com.geneea.celery.examples.TestTaskProxy
import com.geneea.celery.examples.TestVoidTaskProxy
import org.testcontainers.containers.GenericContainer
import spock.genesis.Gen
import spock.lang.Specification

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

class BasicTasksTest extends Specification {
    static final int RABBIT_PORT = 5672
    static final int RABBIT_MANAGEMENT_PORT = 15672

    String rabbitUrl(GenericContainer rabbit, String protocol) {
        return "$protocol://guest:guest@${rabbit.getContainerIpAddress()}:${rabbit.getMappedPort(RABBIT_PORT)}/%2F"
    }

    class RabbitWaitStrategy extends GenericContainer.AbstractWaitStrategy {

        @Override
        protected void waitUntilReady() {
            def f = new ConnectionFactory()
            f.uri = rabbitUrl(container, "amqp")
            Unreliables.retryUntilSuccess(startupTimeout.seconds as int, TimeUnit.SECONDS, {
                f.newConnection(ForkJoinPool.commonPool())
            })
        }
    }

    @Rule
    GenericContainer rabbit = new GenericContainer("rabbitmq:3-management")
            .withExposedPorts(RABBIT_PORT)
            .waitingFor(new RabbitWaitStrategy())

    Celery client
    Thread worker

    def setup() {
        client = Celery.builder().brokerUri(rabbitUrl(rabbit, "amqp")).backendUri(rabbitUrl(rabbit, "rpc")).build()

        worker = Thread.start { it ->
            CeleryWorker.main(["--broker", rabbitUrl(rabbit, "amqp")] as String[])
        }
    }

    def "We should get the result computed by a basic task"() {
        def result
        when:
        result = TestTaskProxy.with(client).sum(a, b)
        then:
        result.get() == new TestTask().sum(a, b)

        where:
        a << Gen.integer(0, (Integer.MAX_VALUE / 2) as int).take(1)
        b << Gen.integer(0, (Integer.MAX_VALUE / 2) as int).take(1)
    }

    def "The future of a void task should be completed eventually"() {
        def result
        when:
        result = TestVoidTaskProxy.with(client).run(-7, 12)
        then:
        result.get() == null
        result.isDone()
    }

    def "The task returning an exception should report it"() {
        WorkerException exception
        when:
        try {
            task(client).get()
        } catch (ExecutionException t) {
            exception = t.cause
        }
        then:
        exception.message == expectedMessage

        where:
        task                                                         | expectedMessage
        {it -> BadTaskProxy.with(it).throwCheckedException() }       | "Exception(null)"
        {it -> BadTaskProxy.with(it).throwUncheckedException() }     | "RuntimeException(null)"
    }
}
