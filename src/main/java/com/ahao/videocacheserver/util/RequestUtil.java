package com.ahao.videocacheserver.util;


import com.ahao.videocacheserver.HttpRequest;
import com.ahao.videocacheserver.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestUtil {
    public static HttpResponse getHttpResponseFromNet(HttpRequest request) {
        Socket socket;
        HttpResponse response = null;
        try {
            int port = 80;
            try {
                port = Integer.parseInt(request.getHeaders().get(Constant.HOST_PORT));
            } catch (Exception ignored) {
            }
            socket = new Socket(request.getHost(), port);
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(request.getHeadText().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            response = HttpResponse.parse(inputStream);
            response.setSocket(socket);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return response;
    }

    public static String getRealHostNameWithPort(HttpRequest request) {
        String url = request.getUrl();
        Matcher matcher = Pattern.compile(Constant.REAL_HOST_NAME + "=([^&]*)").matcher(url);

        if (matcher.find()) {
            String realHostName = matcher.group(1);
            int realHostPort = 80;
            if (realHostName.contains(":")) {
                String[] split = realHostName.split(":");
                realHostName = split[0];
                realHostPort = Integer.parseInt(split[1]);
            }
            return realHostName + ":" + realHostPort;
        }
        return null;
    }
}
