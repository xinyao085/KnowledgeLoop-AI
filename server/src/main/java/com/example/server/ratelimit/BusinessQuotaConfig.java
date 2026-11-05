package com.example.server.ratelimit;

import com.example.server.service.AuthService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class BusinessQuotaConfig implements WebMvcConfigurer {
    private final RequestQuotaService quotas;
    public BusinessQuotaConfig(RequestQuotaService quotas) { this.quotas=quotas; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if(!"POST".equals(request.getMethod())) return true;
                String path=request.getRequestURI().substring(request.getContextPath().length());
                String business=null;
                if(path.matches("/learning/sessions(?:/\\d+/(?:answers|next))?")) business="teaching";
                else if(path.matches("/(?:documents|courses)/\\d+/questions")) business="question";
                else if(path.matches("/documents/\\d+/process") || path.equals("/documents/complete-upload") || path.equals("/documents/upload")) business="parse";
                if(business!=null) quotas.entry(business,(Long)request.getAttribute(AuthService.REQUEST_USER_ID));
                return true;
            }
        }).order(10).addPathPatterns("/learning/**","/documents/**","/courses/**");
    }
}
