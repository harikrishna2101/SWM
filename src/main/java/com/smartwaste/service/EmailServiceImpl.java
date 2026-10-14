package com.smartwaste.service;

import com.smartwaste.entity.Complaint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:solidwasteremoval@gmail.com}")
    private String senderEmail;

    @Value("${server.port:9035}")
    private String serverPort;

    @Value("${app.base-url:${APP_BASE_URL:}}")
    private String appBaseUrl;

    @Value("${brevo.api.key:${BREVO_API_KEY:}}")
    private String brevoApiKey;

    @Value("${resend.api.key:${RESEND_API_KEY:}}")
    private String resendApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @jakarta.annotation.PostConstruct
    public void logEmailConfiguration() {
        if (brevoApiKey != null && !brevoApiKey.trim().isEmpty()) {
            System.out.println(">>> [EMAIL SERVICE] Active Mode: BREVO HTTPS API (Port 443)");
        } else if (resendApiKey != null && !resendApiKey.trim().isEmpty()) {
            System.out.println(">>> [EMAIL SERVICE] Active Mode: RESEND HTTPS API (Port 443)");
        } else {
            System.out.println(">>> [EMAIL SERVICE] Active Mode: SMTP (Gmail / Custom Server on Port 587)");
            System.out.println(">>> [EMAIL SERVICE] NOTE: If running on cloud platforms (e.g., Render), SMTP port 587 is blocked. Make sure BREVO_API_KEY is set in environment variables.");
        }
    }

    private String getBaseUrl() {
        if (appBaseUrl != null && !appBaseUrl.trim().isEmpty()) {
            String url = appBaseUrl.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return "http://localhost:" + serverPort;
    }

    @Override
    public void sendComplaintRegistrationEmail(Complaint complaint, String citizenEmail, String adminEmail) {
        String trackLink = getBaseUrl() + "/user/complaints/" + complaint.getId();
        String citizenSubject = "Complaint Registered Successfully - ID: " + complaint.getComplaintId();
        String citizenBody = "Dear Citizen,\n\n" +
                "Your complaint has been successfully registered.\n\n" +
                "Complaint ID: " + complaint.getComplaintId() + "\n" +
                "Category: " + complaint.getCategory() + "\n\n" +
                "You can track the status of your complaint here: " + trackLink + "\n\n" +
                "Thank you,\nSmart Waste Management System";
        sendEmail(citizenEmail, citizenSubject, citizenBody);

        if (adminEmail != null && !adminEmail.trim().isEmpty()) {
            String reporterName = (complaint.getCitizen() != null && complaint.getCitizen().getName() != null)
                    ? complaint.getCitizen().getName() : "Citizen";
            String adminSubject = "New Complaint Registered - ID: " + complaint.getComplaintId();
            String adminBody = "Dear Admin,\n\n" +
                    "A new complaint has been registered.\n\n" +
                    "Complaint ID: " + complaint.getComplaintId() + "\n" +
                    "Reported by: " + reporterName + " (" + citizenEmail + ")\n" +
                    "Category: " + complaint.getCategory() + "\n\n" +
                    "Please login to the admin dashboard to take action.\n\n" +
                    "Smart Waste Management System";
            sendEmail(adminEmail, adminSubject, adminBody);
        }
    }

    @Override
    public void sendVerificationEmail(String email, String otp) {
        String verifyLink = getBaseUrl() + "/verify?email=" + email + "&otp=" + otp;

        System.out.println("======================================================================");
        System.out.println(">>> [EMAIL SERVICE] OTP FOR [" + email + "] : " + otp);
        System.out.println(">>> [VERIFY LINK] " + verifyLink);
        System.out.println(">>> (If email delivery fails on cloud hosts, use the OTP printed above!)");
        System.out.println("======================================================================");

        String subject = "Verify Your Smart Waste Management Account";
        String body = "Welcome to Smart Waste Management!\n\n" +
                "Your email verification code is: " + otp + "\n\n" +
                "To activate your account instantly, please click the link below:\n" +
                verifyLink + "\n\n" +
                "Thank you,\nSmart Waste Management Team";

        sendEmail(email, subject, body);
    }

    private boolean sendEmail(String toEmail, String subject, String bodyText) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            return false;
        }

        // 1. If Brevo HTTP API Key is configured, send via Brevo (Port 443 - Never blocked on Render)
        if (brevoApiKey != null && !brevoApiKey.trim().isEmpty()) {
            return sendViaBrevoHttp(toEmail, subject, bodyText);
        }

        // 2. If Resend HTTP API Key is configured, send via Resend (Port 443 - Never blocked on Render)
        if (resendApiKey != null && !resendApiKey.trim().isEmpty()) {
            return sendViaResendHttp(toEmail, subject, bodyText);
        }

        // 3. Fallback to standard SMTP (Gmail / Custom Mail Server)
        System.out.println(">>> [EMAIL SERVICE] No Brevo/Resend API key detected in running instance. Attempting standard SMTP (port 587)...");
        return sendViaSmtp(toEmail, subject, bodyText);
    }

    private boolean sendViaBrevoHttp(String toEmail, String subject, String bodyText) {
        try {
            System.out.println(">>> [EMAIL SERVICE] Sending via Brevo HTTPS API to: " + toEmail);
            String html = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e0e0e0; border-radius: 8px;'>"
                    + "<h2 style='color: #198754; text-align: center;'>♻️ Smart Waste Management</h2>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin: 20px 0;'>"
                    + "<p style='font-size: 15px; color: #333; line-height: 1.6;'>" + bodyText.replace("\n", "<br>") + "</p>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin: 20px 0;'>"
                    + "<p style='font-size: 12px; color: #777; text-align: center;'>Smart Waste Management System &bull; Automated Notification</p>"
                    + "</div>";

            String jsonPayload = "{"
                    + "\"sender\":{\"name\":\"Smart Waste Management\",\"email\":\"" + escapeJson(senderEmail) + "\"},"
                    + "\"to\":[{\"email\":\"" + escapeJson(toEmail) + "\"}],"
                    + "\"subject\":\"" + escapeJson(subject) + "\","
                    + "\"textContent\":\"" + escapeJson(bodyText) + "\","
                    + "\"htmlContent\":\"" + escapeJson(html) + "\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .header("accept", "application/json")
                    .header("api-key", brevoApiKey.trim())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println(">>> [EMAIL SERVICE] Successfully sent email via Brevo HTTPS API to: " + toEmail);
                return true;
            } else {
                System.err.println(">>> [EMAIL SERVICE] Brevo API responded with status " + response.statusCode() + ": " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.err.println(">>> [EMAIL SERVICE] Failed sending via Brevo API: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean sendViaResendHttp(String toEmail, String subject, String bodyText) {
        try {
            System.out.println(">>> [EMAIL SERVICE] Sending via Resend HTTPS API to: " + toEmail);
            String from = "Smart Waste Management <onboarding@resend.dev>";
            String html = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e0e0e0; border-radius: 8px;'>"
                    + "<h2 style='color: #198754; text-align: center;'>♻️ Smart Waste Management</h2>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin: 20px 0;'>"
                    + "<p style='font-size: 15px; color: #333; line-height: 1.6;'>" + bodyText.replace("\n", "<br>") + "</p>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin: 20px 0;'>"
                    + "<p style='font-size: 12px; color: #777; text-align: center;'>Smart Waste Management System &bull; Automated Notification</p>"
                    + "</div>";

            String jsonPayload = "{"
                    + "\"from\":\"" + escapeJson(from) + "\","
                    + "\"to\":[\"" + escapeJson(toEmail) + "\"],"
                    + "\"subject\":\"" + escapeJson(subject) + "\","
                    + "\"text\":\"" + escapeJson(bodyText) + "\","
                    + "\"html\":\"" + escapeJson(html) + "\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println(">>> [EMAIL SERVICE] Successfully sent email via Resend HTTPS API to: " + toEmail);
                return true;
            } else {
                System.err.println(">>> [EMAIL SERVICE] Resend API responded with status " + response.statusCode() + ": " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.err.println(">>> [EMAIL SERVICE] Failed sending via Resend API: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean sendViaSmtp(String toEmail, String subject, String bodyText) {
        try {
            System.out.println(">>> [EMAIL SERVICE] Sending via SMTP to: " + toEmail);
            SimpleMailMessage message = new SimpleMailMessage();
            if (senderEmail != null && !senderEmail.trim().isEmpty()) {
                message.setFrom(senderEmail.trim());
            }
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(bodyText);

            mailSender.send(message);
            System.out.println(">>> [EMAIL SERVICE] Successfully sent email via SMTP to: " + toEmail);
            return true;
        } catch (Exception e) {
            System.err.println(">>> [EMAIL SERVICE] FAILED to send email via SMTP to " + toEmail + ": " + e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timed out") || msg.contains("connection refused") || msg.contains("could not connect") || msg.contains("unknownhost")) {
                System.err.println(">>> [RENDER / CLOUD SMTP BLOCK WARNING] Cloud hosting providers like Render Free Tier BLOCK outbound SMTP ports (25, 465, 587).");
                System.err.println(">>> [SOLUTION] Add BREVO_API_KEY or RESEND_API_KEY to Render Environment Variables to send over HTTPS (port 443).");
            }
            e.printStackTrace();
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
