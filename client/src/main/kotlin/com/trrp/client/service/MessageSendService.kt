package com.trrp.client.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trrp.client.model.DataMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

@Service
class MessageSendService(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val REQUEST_QUEUE = "request-queue"
        private const val DATA_QUEUE = "data-queue"
        private val logger = LoggerFactory.getLogger(MessageSendService::class.java)
    }

    private fun encodeMessage(publicRSAKey: String, message: String): DataMessageDTO {
        /* Декодирование RSA ключа */
        val keySpecRSA = X509EncodedKeySpec(Base64.getDecoder().decode(publicRSAKey.toByteArray()))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpecRSA)

        /* Генерация DES ключа */
        val desKey = KeyGenerator.getInstance("DES").generateKey()
        val secretDESKey = Base64.getEncoder().encodeToString(desKey.encoded)

        //logger.info("[CLIENT] : secretDESKey $secretDESKey")

        /* Шифрование DES с помощью RSA */
        val cipherRSA = Cipher.getInstance("RSA")
        cipherRSA.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedDES = cipherRSA.doFinal(secretDESKey.toByteArray())

        /* Шифрование данных с помощью DES */
        val cipherDES: Cipher = Cipher.getInstance("DES")
        val decode = Base64.getDecoder().decode(secretDESKey)
        cipherDES.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decode, "DES"))
        val encryptedMessageBytes = cipherDES.doFinal(message.toByteArray(StandardCharsets.UTF_8))

        return DataMessageDTO(
            decodeKey = Base64.getEncoder().encodeToString(encryptedDES),
            data = Base64.getEncoder().encodeToString(encryptedMessageBytes)
        )
    }

    fun sendReceiveRequestRMQ(message: String) {
        logger.info("[CLIENT] : отправка запроса на получение RSA ключа")
        val publicRSAKey: String?
        try {
            publicRSAKey =
                rabbitTemplate.convertSendAndReceive(REQUEST_QUEUE, "Hi from client! Дай ключ") as String?
        } catch (e: Exception) {
            logger.warn("[CLIENT] : ошибка при отправке или получении запроса к серверу")
            return
        }

        logger.info("[CLIENT] : шифрование и отправка данных")
        val preparedMessage = publicRSAKey?.let { encodeMessage(it, message) }
        if (preparedMessage != null) {
            val orderJson: String = objectMapper.writeValueAsString(preparedMessage)
            val message1: Message = MessageBuilder
                .withBody(orderJson.toByteArray())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build()
            try {
                rabbitTemplate.convertAndSend(DATA_QUEUE, message1)
            } catch (e: Exception) {
                logger.warn("[CLIENT] : ошибка при отправке или получении запроса к серверу")
                return
            }
            logger.info("[CLIENT] : данные отправлены")
        }
    }
}