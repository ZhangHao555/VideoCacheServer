package com.ahao.serverstudy.interceptor;

import com.ahao.serverstudy.HttpRequest;
import com.ahao.serverstudy.HttpResponse;
import com.ahao.serverstudy.exception.RequestException;

public interface Interceptor {
    HttpResponse intercept(Chain chain) throws RequestException;

    interface Chain {
        HttpRequest getRequest();

        HttpResponse proceed(HttpRequest request);
    }
}
