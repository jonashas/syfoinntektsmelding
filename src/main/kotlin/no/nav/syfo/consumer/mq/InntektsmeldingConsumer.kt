package no.nav.syfo.consumer.mq

import com.google.common.util.concurrent.Striped
import log
import no.nav.melding.virksomhet.dokumentnotifikasjon.v1.XMLForsendelsesinformasjon
import no.nav.syfo.consumer.rest.aktor.AktorConsumer
import no.nav.syfo.domain.InntektsmeldingMeta
import no.nav.syfo.domain.JournalStatus
import no.nav.syfo.domain.inntektsmelding.Inntektsmelding
import no.nav.syfo.mapping.mapInntektsmelding
import no.nav.syfo.producer.InntektsmeldingProducer
import no.nav.syfo.repository.InntektsmeldingDAO
import no.nav.syfo.service.JournalpostService
import no.nav.syfo.service.SaksbehandlingService
import no.nav.syfo.util.JAXB
import no.nav.syfo.util.MDCOperations.*
import no.nav.syfo.util.Metrikk
import no.nav.syfo.util.validerInntektsmelding
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Optional.ofNullable
import javax.jms.JMSException
import javax.jms.TextMessage
import javax.xml.bind.JAXBElement

@Component
class InntektsmeldingConsumer(
        private val journalpostService: JournalpostService,
        private val saksbehandlingService: SaksbehandlingService,
        private val metrikk: Metrikk,
        private val inntektsmeldingDAO: InntektsmeldingDAO,
        private val aktorConsumer: AktorConsumer,
        private val inntektsmeldingProducer: InntektsmeldingProducer
) {
    private val consumerLocks = Striped.lock(8)
    private val log = log()

    @Transactional(transactionManager = "jmsTransactionManager")
    @JmsListener(
            id = "inntektsmelding_listener",
            containerFactory = "jmsListenerContainerFactory",
            destination = "inntektsmeldingQueue"
    )
    fun listen(message: Any) {
        try {
            val textMessage = message as TextMessage
            putToMDC(MDC_CALL_ID, ofNullable(textMessage.getStringProperty("callId")).orElse(generateCallId()))
            val xmlForsendelsesinformasjon =
                    JAXB.unmarshalForsendelsesinformasjon<JAXBElement<XMLForsendelsesinformasjon>>(textMessage.text)
            val info = xmlForsendelsesinformasjon.value

            val inntektsmelding = journalpostService.hentInntektsmelding(info.arkivId)
            val consumerLock = consumerLocks.get(inntektsmelding.fnr)

            try {
                consumerLock.lock()
                val aktorid = aktorConsumer.getAktorId(inntektsmelding.fnr)

                metrikk.tellJournalpoststatus(inntektsmelding.journalStatus);

                if (JournalStatus.MIDLERTIDIG == inntektsmelding.journalStatus) {
                    metrikk.tellInntektsmeldingerMottatt(inntektsmelding)

                    val saksId = saksbehandlingService.behandleInntektsmelding(inntektsmelding, aktorid)

                    journalpostService.ferdigstillJournalpost(saksId, inntektsmelding)

                    val validertInntektsmeldingMedId = validerOgLeggPåId(inntektsmelding, aktorid, saksId, textMessage.jmsCorrelationID)

                    inntektsmeldingProducer.leggMottattInntektsmeldingPåTopic(mapInntektsmelding(validertInntektsmeldingMedId, aktorid))

                    log.info("Inntektsmelding {} er journalført for {}", inntektsmelding.journalpostId, textMessage.jmsCorrelationID ?: "UKJENT")
                } else {
                    log.info(
                            "Behandler ikke inntektsmelding {} da den har status: {}",
                            inntektsmelding.journalpostId,
                            inntektsmelding.journalStatus
                    )
                }
            } finally {
                consumerLock.unlock()
            }
        } catch (e: JMSException) {
            log.error("Feil ved parsing av inntektsmelding fra kø", e)
            metrikk.tellInntektsmeldingfeil()
            throw RuntimeException("Feil ved lesing av melding", e)
        } catch (e: Exception) {
            log.error("Det skjedde en feil ved journalføring", e)
            metrikk.tellInntektsmeldingfeil()
            throw RuntimeException("Det skjedde en feil ved journalføring", e)
        } finally {
            remove(MDC_CALL_ID)
        }
    }

    private fun validerOgLeggPåId(inntektsmelding: Inntektsmelding, aktorid: String, saksId: String, arkivreferanse: String?): Inntektsmelding {
        val id = lagreBehandling(inntektsmelding, aktorid, saksId)
        val gyldighet = validerInntektsmelding(inntektsmelding)
        return inntektsmelding.copy(id = id, arkivRefereranse = arkivreferanse
                ?: "UKJENT", gyldighetsStatus = gyldighet)
    }

    private fun lagreBehandling(inntektsmelding: Inntektsmelding, aktorid: String, saksId: String): String {
        val inntektsmeldingMeta = InntektsmeldingMeta(
                orgnummer = inntektsmelding.arbeidsgiverOrgnummer,
                arbeidsgiverPrivat = inntektsmelding.arbeidsgiverPrivatFnr,
                arbeidsgiverperioder = inntektsmelding.arbeidsgiverperioder,
                aktorId = aktorid,
                sakId = saksId,
                journalpostId = inntektsmelding.journalpostId,
                behandlet = LocalDateTime.now()
        )
        return inntektsmeldingDAO.opprett(
                inntektsmeldingMeta
        )
    }
}
