package org.openvasp.client.session.impl;

import lombok.NonNull;
import lombok.Setter;
import lombok.val;
import org.openvasp.client.common.VaspException;
import org.openvasp.client.config.VaspConfig;
import org.openvasp.client.model.*;
import org.openvasp.client.service.*;
import org.openvasp.client.session.BeneficiarySession;
import org.openvasp.client.session.OriginatorSession;
import org.openvasp.client.session.Session;
import org.openvasp.client.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * @author Olexandr_Bilovol@epam.com
 */
@Singleton
public final class SessionManagerImpl implements SessionManager, TopicListener {

    final ContractService contractService;
    final MessageService messageService;

    @Setter
    BiConsumer<VaspMessage, Session> customMessageHandler;

    @Setter
    BiConsumer<VaspException, Session> customErrorHandler;

    final VaspInfo vaspInfo;
    final String handshakePrivateKey;

    private final ConcurrentMap<String, BeneficiarySessionImpl> beneficiarySessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OriginatorSessionImpl> originatorSessions = new ConcurrentHashMap<>();

    private final Lock sessionsLock = new ReentrantLock();
    private final Condition newBeneficiarySessionsCondition = sessionsLock.newCondition();
    private final Condition noActiveSessionsCondition = sessionsLock.newCondition();

    @Inject
    public SessionManagerImpl(
            final VaspConfig vaspConfig,
            final ContractService contractService,
            final MessageService messageService) {

        this.contractService = contractService;
        this.messageService = messageService;

        this.handshakePrivateKey = vaspConfig.getHandshakePrivateKey();
        this.vaspInfo = vaspConfig.getVaspInfo();

        messageService.addTopicListener(
                vaspConfig.getVaspCode().toTopic(),
                EncryptionType.ASSYMETRIC,
                handshakePrivateKey,
                this);
    }

    VaspCode vaspCode() {
        return vaspInfo.getVaspCode();
    }

    @Override
    public OriginatorSession createOriginatorSession(@NonNull final TransferInfo transferInfo) {
        val result = new OriginatorSessionImpl(this, transferInfo);
        result.setCustomMessageHandler(customMessageHandler);
        result.setCustomErrorHandler(customErrorHandler);

        addOriginatorSession(result);

        messageService.addTopicListener(
                result.incomingMessageTopic(),
                EncryptionType.SYMMETRIC,
                result.sharedSecret(),
                result);

        return result;
    }

    @Override
    public void onReceiveMessage(@NonNull final TopicEvent event) {
        val message = event.getMessage();

        // At the level of the VASP instance we process only session requests
        if (message instanceof SessionRequest) {
            val sessionRequest = (SessionRequest) message;

            val beneficiarySession = new BeneficiarySessionImpl(this, sessionRequest);
            beneficiarySession.setCustomMessageHandler(customMessageHandler);
            beneficiarySession.setCustomErrorHandler(customErrorHandler);
            beneficiarySession.addIncomingMessage(message);

            addBenefeciarySession(beneficiarySession);

            messageService.addTopicListener(
                    beneficiarySession.incomingMessageTopic(),
                    EncryptionType.SYMMETRIC,
                    beneficiarySession.sharedSecret(),
                    beneficiarySession);

            if (customMessageHandler != null) {
                customMessageHandler.accept(sessionRequest, beneficiarySession);
            }
        }
    }

    @Override
    public void onError(@NonNull final TopicErrorEvent event) {
        if (customErrorHandler != null) {
            customErrorHandler.accept(event.getCause(), null);
        }
    }

    @Override
    public Optional<BeneficiarySession> waitForBeneficiarySession(
            @NonNull final String sessionId,
            final long msTimeout) {

        sessionsLock.lock();
        try {
            if (beneficiarySessions.containsKey(sessionId)) {
                return Optional.of(beneficiarySessions.get(sessionId));
            } else {
                return newBeneficiarySessionsCondition.await(msTimeout, TimeUnit.MILLISECONDS)
                        ? getBeneficiarySession(sessionId)
                        : Optional.empty();
            }
        } catch (InterruptedException ex) {
            return Optional.empty();
        } finally {
            sessionsLock.unlock();
        }
    }

    @Override
    public boolean waitForNoActiveSessions(final long msTimeout) {
        sessionsLock.lock();
        try {
            return originatorSessions.isEmpty() && beneficiarySessions.isEmpty() ||
                    noActiveSessionsCondition.await(msTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            return false;
        } finally {
            sessionsLock.unlock();
        }
    }

    void addOriginatorSession(@NonNull final OriginatorSessionImpl originatorSession) {
        sessionsLock.lock();
        try {
            originatorSessions.put(originatorSession.sessionId(), originatorSession);
        } finally {
            sessionsLock.unlock();
        }
    }

    void addBenefeciarySession(@NonNull final BeneficiarySessionImpl beneficiarySession) {
        sessionsLock.lock();
        try {
            beneficiarySessions.put(beneficiarySession.sessionId(), beneficiarySession);
            newBeneficiarySessionsCondition.signalAll();
        } finally {
            sessionsLock.unlock();
        }
    }

    void removeOriginatorSession(@NonNull final OriginatorSessionImpl originatorSession) {
        sessionsLock.lock();
        try {
            originatorSessions.remove(originatorSession.sessionId());
            messageService.removeTopicListener(originatorSession.incomingMessageTopic(), originatorSession);
            if (originatorSessions.isEmpty() && beneficiarySessions.isEmpty()) {
                noActiveSessionsCondition.signalAll();
            }
        } finally {
            sessionsLock.unlock();
        }
    }

    void removeBenefeciarySession(@NonNull final BeneficiarySessionImpl beneficiarySession) {
        sessionsLock.lock();
        try {
            beneficiarySessions.remove(beneficiarySession.sessionId());
            messageService.removeTopicListener(beneficiarySession.incomingMessageTopic(), beneficiarySession);
            if (originatorSessions.isEmpty() && beneficiarySessions.isEmpty()) {
                noActiveSessionsCondition.signalAll();
            }
        } finally {
            sessionsLock.unlock();
        }
    }

    @Override
    public Optional<OriginatorSession> getOriginatorSession(@NonNull final String sessionId) {
        val result = originatorSessions.get(sessionId);
        return result == null ? Optional.empty() : Optional.of(result);
    }

    @Override
    public Optional<BeneficiarySession> getBeneficiarySession(@NonNull final String sessionId) {
        val result = beneficiarySessions.get(sessionId);
        return result == null ? Optional.empty() : Optional.of(result);
    }

    @Override
    public List<Session> allSessions() {
        sessionsLock.lock();
        try {
            val result = new ArrayList<Session>();
            result.addAll(originatorSessions.values());
            result.addAll(beneficiarySessions.values());
            return result;
        } finally {
            sessionsLock.unlock();
        }
    }

    @Override
    public Session restoreSession(@NonNull final Session.State sessionState) {
        return sessionState.getType() == Session.Type.ORIGINATOR
                ? restoreOriginatorSession(sessionState)
                : restoreBeneficiarySession(sessionState);
    }

    private Session restoreOriginatorSession(final Session.State sessionState) {
        // TODO: to be implemented
        throw new UnsupportedOperationException();
    }

    private Session restoreBeneficiarySession(final Session.State sessionState) {
        // TODO: to be implemented
        throw new UnsupportedOperationException();
    }

}