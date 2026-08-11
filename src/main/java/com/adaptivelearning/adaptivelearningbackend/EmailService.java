package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Sends the registration OTP email via Resend's HTTPS REST API rather than
 * SMTP — Railway (and most PaaS platforms) block outbound SMTP ports 25/465/
 * 587 as an anti-spam measure, so raw SMTP calls just timed out at the TCP
 * level regardless of credentials. HTTPS (443) isn't blocked.
 *
 * Requires: resend.api.key and app.mail.from in config/env. Get a key at
 * https://resend.com/api-keys (free tier: 3,000/month, 100/day). For testing,
 * app.mail.from can stay "onboarding@resend.dev" (no domain verification needed).
 */
@Service
public class EmailService {

    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    @Value("${resend.api.key}")
    private String resendApiKey;

    @Value("${app.mail.from}")
    private String fromAddress;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Sends a 6-digit OTP so the user can confirm ownership before their account is created. */
    public void sendVerificationOtp(String toEmail, String otp, String fullName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(resendApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String html = buildOtpEmail(fullName, otp);

            Map<String, Object> body = Map.of(
                    "from", fromAddress,
                    "to", List.of(toEmail),
                    "subject", "Your verification code – Learning System",
                    "html", html
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(RESEND_API_URL, request, String.class);

            System.out.println("Verification OTP sent to: " + toEmail + " (Resend response: " + response.getStatusCode() + ")");

        } catch (HttpClientErrorException e) {
            // Resend's 4xx body usually names the exact problem (bad key, unverified domain, etc).
            String responseBody = e.getResponseBodyAsString();
            System.err.println("Failed to send verification email to " + toEmail
                    + ": Resend API error (" + e.getStatusCode() + "): " + responseBody);
            throw new RuntimeException("Could not send verification email. Please check your email address and try again.", e);
        } catch (Exception e) {
            System.err.println("Failed to send verification email to " + toEmail + ": " + e.getMessage());
            throw new RuntimeException("Could not send verification email. Please check your email address and try again.", e);
        }
    }

    /** Simple, self-contained HTML so it renders correctly across major email clients. */
    private String buildOtpEmail(String fullName, String otp) {
        String displayName = (fullName != null && !fullName.isBlank()) ? fullName : "there";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Verify your email</title>
                </head>
                <body style="margin:0;padding:0;background:#f3f6f9;font-family:system-ui,-apple-system,'Segoe UI',Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f3f6f9;padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table width="480" cellpadding="0" cellspacing="0"
                               style="background:#ffffff;border-radius:16px;border:1px solid #e2e8f0;
                                      box-shadow:0 4px 16px rgba(0,0,0,0.06);overflow:hidden;max-width:100%%;">

                          <!-- Header -->
                          <tr>
                            <td style="background:#192a3e;padding:28px 32px;">
                              <div style="display:inline-block;background:#FFD400;border-radius:10px;
                                          width:36px;height:36px;line-height:36px;text-align:center;
                                          font-weight:800;color:#002D72;font-size:18px;vertical-align:middle;">L</div>
                              <span style="color:#ffffff;font-weight:700;font-size:16px;
                                           vertical-align:middle;margin-left:10px;">Learning System</span>
                            </td>
                          </tr>

                          <!-- Body -->
                          <tr>
                            <td style="padding:36px 32px 28px;">
                              <p style="margin:0 0 6px;font-size:22px;font-weight:800;color:#111827;">
                                Verify your email 📬
                              </p>
                              <p style="margin:0 0 24px;font-size:15px;color:#6b7280;line-height:1.6;">
                                Hi %s, enter the code below to complete your registration.
                                It expires in <strong>5 minutes</strong>.
                              </p>

                              <!-- OTP box -->
                              <div style="background:#f0f9ff;border:2px solid #bae6fd;border-radius:14px;
                                          padding:28px;text-align:center;margin-bottom:24px;">
                                <div style="font-size:42px;font-weight:900;letter-spacing:14px;
                                            color:#0093ad;line-height:1;">%s</div>
                                <div style="font-size:13px;color:#6b7280;margin-top:10px;">
                                  Your one-time verification code
                                </div>
                              </div>

                              <p style="margin:0;font-size:13px;color:#9ca3af;line-height:1.6;">
                                If you didn't request this, you can safely ignore this email —
                                no account will be created without the code.
                              </p>
                            </td>
                          </tr>

                          <!-- Footer -->
                          <tr>
                            <td style="background:#f8fafc;border-top:1px solid #e2e8f0;
                                        padding:16px 32px;text-align:center;">
                              <p style="margin:0;font-size:12px;color:#9ca3af;">
                                © Learning System · This is an automated message, please do not reply.
                              </p>
                            </td>
                          </tr>

                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(displayName, otp);
    }
}