package dev.challenge.activation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    RequestBodyLimitFilter(@Value("${activation.api.max-request-body-size:16KB}") DataSize maxSize,
                           ObjectMapper objectMapper) {
        this.maxBytes = maxSize.toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            Problems.write(response, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, ProblemType.PAYLOAD_TOO_LARGE,
                    new RequestBodyTooLargeException(maxBytes).getMessage(), request), objectMapper);
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream limitedStream;

        LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedStream == null) {
                limitedStream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return limitedStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name();
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        private void count(int read) throws RequestBodyTooLargeException {
            bytesRead += read;
            if (bytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }
    }
}
