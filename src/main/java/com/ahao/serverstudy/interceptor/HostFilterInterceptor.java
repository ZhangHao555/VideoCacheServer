package com.ahao.serverstudy.interceptor;


import com.ahao.serverstudy.HttpRequest;
import com.ahao.serverstudy.HttpResponse;
import com.ahao.serverstudy.util.Constant;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HostFilterInterceptor implements Interceptor {
    private final static Logger logger = Logger.getLogger("CacheInterceptor");

    @Override
    public HttpResponse intercept(Chain chain) {
        HttpRequest request = chain.getRequest();
        String url = request.getUrl();
        Matcher matcher = Pattern.compile(Constant.REAL_HOST_NAME + "=([^&]*)").matcher(url);

        if (!matcher.find() || matcher.groupCount() < 1) {
            return HttpResponse.get404Response();
        } else {
            String realHostName = matcher.group(1);
            int realHostPort = 80;
            if (realHostName.contains(":")) {
                String[] split = realHostName.split(":");
                realHostName = split[0];
                realHostPort = Integer.parseInt(split[1]);
            }

            String host = request.getHost();
            if (host != null) {
                host = host.trim();
            }
            request.getHeaders().put(Constant.PROXY_HOST, host);
            request.getHeaders().put(Constant.HOST, realHostName);
            request.getHeaders().put(Constant.HOST_PORT, String.valueOf(realHostPort));
            request.getHeaders().remove(Constant.IF_RANGE);
            request.getHeaders().put(Constant.CONNECTION, "close");

            if (request.getHeaders().get(Constant.REFERER) != null) {
                String referer = String.format("http://%s:%s%s", realHostName, realHostPort, url);
                request.getHeaders().put(Constant.REFERER, referer);
            }

            logger.log(Level.INFO, "after host filter \n");
            logger.log(Level.INFO, request.getHeadText());
            return chain.proceed(request);
        }
    }

}
