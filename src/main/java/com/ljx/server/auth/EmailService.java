package com.ljx.server.auth;

import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
public class EmailService {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);

    private final EmailBindingRepository bindingRepository;
    private final MailSender mailSender;
    private final AuditService auditService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public EmailService(EmailBindingRepository bindingRepository, MailSender mailSender,
                        AuditService auditService) {
        this.bindingRepository = bindingRepository;
        this.mailSender = mailSender;
        this.auditService = auditService;
    }

    @Transactional
    public void sendCode(Long accountId, String email) {
        if (!EMAIL.matcher(email).matches()) {
            throw new BizException(ErrorCode.BAD_EMAIL);
        }
        EmailBinding binding = bindingRepository.findById(accountId).orElseGet(() -> new EmailBinding(accountId));
        if (binding.getCodeSentAt() != null
                && binding.getCodeSentAt().plus(SEND_COOLDOWN).isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.MAIL_TOO_FREQUENT);
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        LocalDateTime now = LocalDateTime.now();
        binding.sendCode(email, encoder.encode(code), now.plus(CODE_TTL), now);
        bindingRepository.save(binding);
        try {
            mailSender.send(email, "垃圾侠邮箱验证码", code);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    @Transactional
    public void bind(Long accountId, String email, String code) {
        if (!EMAIL.matcher(email).matches()) {
            throw new BizException(ErrorCode.BAD_EMAIL);
        }
        EmailBinding binding = bindingRepository.findById(accountId)
                .filter(b -> email.equals(b.getEmail()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_EMAIL_CODE));
        boolean valid = binding.getCodeHash() != null
                && binding.getCodeExpiresAt() != null
                && binding.getCodeExpiresAt().isAfter(LocalDateTime.now())
                && encoder.matches(code, binding.getCodeHash());
        if (!valid) {
            throw new BizException(ErrorCode.BAD_EMAIL_CODE);
        }
        binding.markVerified(email);
        bindingRepository.save(binding);
        auditService.record(accountId, AuditAction.EMAIL_BIND, email, true);
    }
}
