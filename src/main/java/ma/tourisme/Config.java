package ma.tourisme;

import java.nio.file.Path;
import java.util.Locale;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.i18n.*;

@Configuration
public class Config {
    @Bean LocaleResolver localeResolver() { var r = new CookieLocaleResolver("lang"); r.setDefaultLocale(Locale.FRENCH); return r; }

    @Bean WebMvcConfigurer mvc(@Value("${app.images-dir:images}") String dir) {
        return new WebMvcConfigurer() {
            public void addInterceptors(InterceptorRegistry r) { var i = new LocaleChangeInterceptor(); i.setParamName("lang"); r.addInterceptor(i); }
            /** /images/** est servi depuis le dossier images/ du projet (modifiable sans recompiler). */
            public void addResourceHandlers(ResourceHandlerRegistry r) {
                String loc = Path.of(dir).toAbsolutePath().toUri().toString();
                if (!loc.endsWith("/")) loc += "/";
                r.addResourceHandler("/images/**").addResourceLocations(loc, "classpath:/static/images/");
            }
        };
    }

    @Bean PasswordEncoder enc() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService uds(MongoTemplate t) {
        return u -> {
            Document d = t.findOne(Query.query(Criteria.where("username").is(u)), Document.class, "users");
            if (d == null) throw new UsernameNotFoundException(u);
            return User.withUsername(u).password(d.getString("password")).roles("USER").build();
        };
    }

    @Bean SecurityFilterChain chain(HttpSecurity h) throws Exception {
        h.authorizeHttpRequests(a -> a.requestMatchers("/favorites", "/fav").authenticated().anyRequest().permitAll())
         .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true))
         .logout(l -> l.logoutSuccessUrl("/"));
        return h.build();
    }
}
