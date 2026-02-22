package com.platform.subscription_manager.shared.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Transparently encrypts/decrypts the payment_token column using AES-256-GCM.
 *
 * The rest of the codebase works with plain String tokens — encryption is
 * an infrastructure concern that lives only here.
 *
 * The key must be a Base64-encoded 32-byte (256-bit) value supplied via the
 * PAYMENT_TOKEN_ENCRYPTION_KEY environment variable. Never hardcode it.
 *
 * To generate a key:
 *   openssl rand -base64 32
 */
@Converter
@Component
public class PaymentTokenConverter implements AttributeConverter<String, String> {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 128;

	private final SecretKey secretKey;

	public PaymentTokenConverter(
		@Value("${payment.token.encryption-key}") String base64Key) {
		byte[] keyBytes = Base64.getDecoder().decode(base64Key);
		if (keyBytes.length != 32) {
			throw new IllegalArgumentException("payment.token.encryption-key must be a Base64-encoded 32-byte (256-bit) key");
		}
		this.secretKey = new SecretKeySpec(keyBytes, "AES");
	}

	@Override
	public String convertToDatabaseColumn(String plainToken) {
		if (plainToken == null) return null;
		try {
			byte[] iv = new byte[GCM_IV_LENGTH];
			new SecureRandom().nextBytes(iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

			byte[] encrypted = cipher.doFinal(plainToken.getBytes());

			// Prepend IV to the ciphertext so we can extract it on decryption
			byte[] ivPlusCiphertext = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, ivPlusCiphertext, 0, iv.length);
			System.arraycopy(encrypted, 0, ivPlusCiphertext, iv.length, encrypted.length);

			return Base64.getEncoder().encodeToString(ivPlusCiphertext);
		} catch (Exception e) {
			throw new IllegalStateException("Falha ao encriptar o token de pagamento", e);
		}
	}

	@Override
	public String convertToEntityAttribute(String encryptedToken) {
		if (encryptedToken == null) return null;
		try {
			byte[] ivPlusCiphertext = Base64.getDecoder().decode(encryptedToken);

			byte[] iv = new byte[GCM_IV_LENGTH];
			System.arraycopy(ivPlusCiphertext, 0, iv, 0, iv.length);

			byte[] ciphertext = new byte[ivPlusCiphertext.length - iv.length];
			System.arraycopy(ivPlusCiphertext, iv.length, ciphertext, 0, ciphertext.length);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

			return new String(cipher.doFinal(ciphertext));
		} catch (Exception e) {
			throw new IllegalStateException("Falha ao desencriptar o token de pagamento", e);
		}
	}
}

