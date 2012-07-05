package grails.plugin.facebooksdk;

import com.restfb.DefaultFacebookClient;
import com.restfb.DefaultJsonMapper;
import com.restfb.DefaultWebRequestor;

import java.net.HttpURLConnection;

public class FacebookBaseClient extends DefaultFacebookClient {

    // Override default web requestor to add read timeout parameter
    FacebookBaseClient(String accessToken, final Integer timeout) {
        super(accessToken,
                new DefaultWebRequestor() {
                    @Override
                    protected void customizeConnection(HttpURLConnection connection) {
                        connection.setReadTimeout(timeout);
                    }
                },
                new DefaultJsonMapper());
    }

}
