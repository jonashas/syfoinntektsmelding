package no.nav.syfo.integration.kafka

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.GenericAvroDeserializer
import io.ktor.config.*
import no.nav.helse.arbeidsgiver.system.getString
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer

private fun envOrThrow(envVar: String) =
    System.getenv()[envVar] ?: throw IllegalStateException("$envVar er påkrevd miljøvariabel")

private fun consumerOnPremProperties(config: ApplicationConfig) = mutableMapOf<String, Any>(
    ConsumerConfig.MAX_POLL_RECORDS_CONFIG to "1",
    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
    CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to config.getString("kafka_bootstrap_servers"),
    CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to "SASL_SSL",
    SaslConfigs.SASL_MECHANISM to "PLAIN",
    SaslConfigs.SASL_JAAS_CONFIG to "org.apache.kafka.common.security.plain.PlainLoginModule required " +
        "username=\"${config.getString("srvsyfoinntektsmelding.username")}\" password=\"${config.getString("srvsyfoinntektsmelding.password")}\";"
)

private fun consumerLocalProperties(config: ApplicationConfig) = mutableMapOf<String, Any>(
    ConsumerConfig.MAX_POLL_RECORDS_CONFIG to "1",
    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
    CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to config.getString("kafka_bootstrap_servers"),
)

fun joarkLocalProperties(config: ApplicationConfig) = consumerLocalProperties(config) +  mapOf(
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to GenericAvroDeserializer::class.java,
    AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to "http://kafka-schema-registry.tpa.svc.nais.local:8081",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "none",
    ConsumerConfig.GROUP_ID_CONFIG to "syfoinntektsmelding-v2")

fun joarkOnPremProperties(config: ApplicationConfig) = consumerOnPremProperties(config) +  mapOf(
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to GenericAvroDeserializer::class.java,
    AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to "http://kafka-schema-registry.tpa.svc.nais.local:8081",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "none",
    ConsumerConfig.GROUP_ID_CONFIG to "syfoinntektsmelding-v2")

fun utsattOppgaveLocalProperties(config: ApplicationConfig) = consumerLocalProperties(config) + mapOf(
    ConsumerConfig.GROUP_ID_CONFIG to "syfoinntektsmelding-v1",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to UtsattOppgaveDTODeserializer::class.java
)

fun utsattOppgaveOnPremProperties(config: ApplicationConfig) = consumerOnPremProperties(config) + mapOf(
    ConsumerConfig.GROUP_ID_CONFIG to "syfoinntektsmelding-v1",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to UtsattOppgaveDTODeserializer::class.java
)
