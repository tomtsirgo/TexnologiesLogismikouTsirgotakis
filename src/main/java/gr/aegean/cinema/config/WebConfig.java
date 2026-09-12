package gr.aegean.cinema.config;

import gr.aegean.cinema.security.IdempotencyInterceptor;
import gr.aegean.cinema.security.RateLimitInterceptor;
import gr.aegean.cinema.security.TokenAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the three request-scoped cross-cutting concerns onto {@code /api/**}.
 *
 * <h2>Why this order, and why it is not arbitrary</h2>
 * <ol>
 *   <li>{@link TokenAuthInterceptor} — validates the bearer token and publishes
 *       the caller into {@link gr.aegean.cinema.security.CurrentUserContext}.
 *       Everything after it depends on knowing who is calling.</li>
 *   <li>{@link RateLimitInterceptor} — needs the authenticated user to key the
 *       token bucket per user rather than per connection (ASSUMPTIONS.md #4), so
 *       it must run after authentication. It also runs before idempotency, so a
 *       flood of repeated keys still costs the caller their allowance.</li>
 *   <li>{@link IdempotencyInterceptor} — last, because a replayed response
 *       short-circuits the handler, and that decision should only be taken for a
 *       caller who is authenticated and within their rate limit.</li>
 * </ol>
 *
 * <p>The storing half of idempotency lives in
 * {@link gr.aegean.cinema.security.IdempotencyFilter}, which is a servlet filter
 * rather than an interceptor because capturing the response body requires
 * wrapping the response before the DispatcherServlet sees it. Spring Boot
 * registers it automatically as a {@code Filter} bean; it wraps nothing for
 * requests that do not carry an {@code Idempotency-Key}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenAuthInterceptor tokenAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    public WebConfig(TokenAuthInterceptor tokenAuthInterceptor,
                     RateLimitInterceptor rateLimitInterceptor,
                     IdempotencyInterceptor idempotencyInterceptor) {
        this.tokenAuthInterceptor = tokenAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
