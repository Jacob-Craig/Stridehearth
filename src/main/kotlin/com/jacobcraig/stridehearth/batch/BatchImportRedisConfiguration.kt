package com.jacobcraig.stridehearth.batch

import io.lettuce.core.RedisException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration

@Configuration
@EnableScheduling
class BatchImportRedisConfiguration(
    private val redisConnectionFactory: RedisConnectionFactory,
    private val stringRedisTemplate: StringRedisTemplate,
    private val properties: BatchImportProperties,
    private val worker: BatchImportWorker,
) {
    private val logger = LoggerFactory.getLogger(BatchImportRedisConfiguration::class.java)

    @Bean
    fun streamMessageListenerContainer(): StreamMessageListenerContainer<String, MapRecord<String, String, String>> {
        initConsumerGroup()

        val executor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = properties.concurrency
                maxPoolSize = properties.concurrency
                setThreadNamePrefix("import-worker-")
                initialize()
            }

        val options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .batchSize(1)
                .executor(executor)
                .errorHandler { throwable ->
                    if (throwable is RedisSystemException || throwable.cause is RedisException) {
                        logger.debug("Redis stream polling state change: {}", throwable.message)
                    } else {
                        logger.error("Error in Redis stream polling task", throwable)
                    }
                }.build()

        val container = StreamMessageListenerContainer.create(redisConnectionFactory, options)

        for (i in 0 until properties.concurrency) {
            container.receive(
                Consumer.from(properties.group, "worker-$i"),
                StreamOffset.create(properties.stream, ReadOffset.lastConsumed()),
                worker,
            )
        }

        logger.info(
            "Configured Redis Streams listener container on stream '{}', group '{}' with concurrency {}",
            properties.stream,
            properties.group,
            properties.concurrency,
        )

        return container
    }

    private fun initConsumerGroup() {
        try {
            stringRedisTemplate.execute { connection ->
                try {
                    connection.streamCommands().xGroupCreate(
                        properties.stream.toByteArray(),
                        properties.group,
                        ReadOffset.from("0"),
                        true,
                    )
                    logger.info("Created Redis stream consumer group '{}' on '{}'", properties.group, properties.stream)
                } catch (e: Exception) {
                    logger.debug("Redis stream group init note: {}", e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to initialize Redis stream consumer group: {}", e.message)
        }
    }
}
