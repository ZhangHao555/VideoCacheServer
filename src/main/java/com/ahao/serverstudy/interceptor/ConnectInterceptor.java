package com.ahao.serverstudy.interceptor;

import com.ahao.serverstudy.util.RequestUtil;
import com.ahao.serverstudy.HttpRequest;
import com.ahao.serverstudy.HttpResponse;
import com.ahao.serverstudy.exception.RequestException;

public class ConnectInterceptor implements Interceptor {

    @Override
    public HttpResponse intercept(Chain chain) throws RequestException {
        HttpRequest request = chain.getRequest();
        HttpResponse response = RequestUtil.getHttpResponseFromNet(request);

        if (!response.isOK()) {
            throw new RequestException("request not ok :" + response.getHeadText());
        }
        return response;
    }

}
