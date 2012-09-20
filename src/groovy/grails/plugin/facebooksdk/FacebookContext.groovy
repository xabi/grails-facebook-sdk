package grails.plugin.facebooksdk


import org.apache.log4j.Logger

import org.codehaus.groovy.grails.web.mapping.LinkGenerator
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.beans.factory.InitializingBean
import org.springframework.web.context.request.RequestContextHolder
import org.codehaus.groovy.grails.commons.GrailsApplication

class FacebookContext implements InitializingBean {

    private final static List DROP_QUERY_PARAMS = ['code','state','signed_request']

    def grailsLinkGenerator // Injected by Spring
    def grailsApplication // Injected by Spring

    FacebookContextApp app
    FacebookContextPage page // Only if app is running in a page tab and signed request exists in params (initial request)
    FacebookSignedRequest signedRequest = new FacebookSignedRequest()
    FacebookContextUser user

    private FacebookSessionScope _sessionScope
    private FacebookCookieScope _cookieScope
    private Logger log = Logger.getLogger(getClass())

    void afterPropertiesSet() {
        def appConfig
        String appIdParamName = config.appIdParamName ?: 'app_id'
        if (config.apps) {
            // Multiple app config
            if (request.params[appIdParamName]) {
                appConfig = config.apps.find { it.id == request.params[appIdParamName].toLong() }
            } else {
                appConfig = config.apps.find { it.controller == request.params.controller }
            }
        }

        if (!appConfig && config.app) {
            // Single app config
            appConfig = config.app
        } else if (config.appId && config.appSecret) {
            // Single app legacy config
            appConfig = [
                    id:  config.appId,
                    secret: config.appSecret
            ]
            if (config.appPermissions) appConfig = config.appPermissions.tokenize(',') ?: []
        }

        if (!appConfig && config.apps) assert request.params[appIdParamName], 'Invalid facebook config, for multiple apps, app_id (or custom appIdParamName) must be passed in params'
        assert appConfig, 'Facebook app config not found'
        assert appConfig.id && appConfig.secret, 'Invalid Facebook app config, appId and appSecret must be defined'

        app = new FacebookContextApp(
                id: appConfig.id,
                context: this,
                permissions: appConfig.permissions ?: [],
                secret: appConfig.secret
        )

        user = new FacebookContextUser(
                context: this
        )

        if (request.params['signed_request']) {
            // apps.facebook.com (default iframe page or page tab)
            signedRequest = new FacebookSignedRequest(app.secret, request.params['signed_request'], FacebookSignedRequest.TYPE_PARAMS)
            if (signedRequest.accessToken && signedRequest.userId) {
                if (signedRequest.user.age) user.age = signedRequest.user.age
                if (signedRequest.user.country) user.country = signedRequest.user.country
                if (signedRequest.user.locale) user.locale = new Locale(signedRequest.user.locale.tokenize('_')[0], signedRequest.user.locale.tokenize('_')[1])
                // Load token in session scope
                user.token
            }
            if (signedRequest.page) {
                page = new FacebookContextPage(
                    admin: signedRequest.page.admin ?: false,
                    id: signedRequest.page.id.toLong(),
                    liked: signedRequest.page.liked ?: false
                )
            }
            if (signedRequest.appData) app.data = signedRequest.appData
            log.debug "Got signed request from params"
        } else if (cookie.value) {
            // Cookie created by Facebook Connect Javascript SDK
            signedRequest = new FacebookSignedRequest(app.secret, cookie.value, FacebookSignedRequest.TYPE_COOKIE)
            log.debug "Got signed request from cookie"
        }
    }

    boolean isAuthenticated() {
        user?.id ? true : false
    }

    /*
    * @description Cookie scope proxy linked to current facebook app.
    */
    FacebookCookieScope getCookie() {
        if (!_cookieScope) {
            _cookieScope = new FacebookCookieScope(appId: app.id)
        }
        return _cookieScope
    }

    /*
    * @description Session scope proxy linked to current facebook app.
    */
    FacebookSessionScope getSession() {
        if (!_sessionScope) {
            _sessionScope = new FacebookSessionScope(appId: app.id)
        }
        return _sessionScope
    }

    /*
    * @description Get a login status URL to fetch the status from facebook.
    * @hint
    * Available parameters:
    * - ok_session: the URL to go to if a session is found
    * - no_session: the URL to go to if the user is not connected
    * - no_user: the URL to go to if the user is not signed into facebook
    */
    String getLoginStatusUrl(Map parameters = [:]) {
        if (!request.params['api_key']) parameters['api_key'] = app.id
        if (!request.params['no_session']) parameters['no_session'] = currentURL
        if (!request.params['no_user']) parameters['no_user'] = currentURL
        if (!request.params['ok_session']) parameters['ok_session'] = currentURL
        if (!request.params['session_version']) parameters['session_version'] = 3
        return buildFacebookURL("extern/login_status.php", parameters)
    }

    /*
     * @description Get a Login URL for use with redirects.
     * @hint By default, full page redirect is assumed. If you are using the generated URL with a window.open() call in JavaScript, you can pass in display=popup as part of the parameters.
     * Available parameters:
          * - redirect_uri: the url to go to after a successful login
          * - scope: comma separated list of requested extended perms
     */
    String getLoginURL(Map parameters = [:]) {
        String state = UUID.randomUUID().encodeAsMD5()
        session.setData('state', state)
        if (!parameters['client_id']) parameters['client_id'] = app.id
        if (!parameters['redirect_uri']) parameters['redirect_uri'] = currentURL
        if (!parameters['scope']) parameters['scope'] = app.permissions.join(',')
        if (!parameters['state']) parameters['state'] = state
        return buildFacebookURL('dialog/oauth', parameters)
    }

    /*
     * @description Get a Logout URL suitable for use with redirects.
     * @hint
     * Available parameters:
          * - next: the url to go to after a successful logout
     */
    String getLogoutURL(Map parameters = [:]) {
        if (!parameters['access_token']) parameters['access_token'] = user.token
        if (!parameters['next']) currentURL
        return buildFacebookURL('logout.php', parameters)
    }

    String toString() {
        "FacebookContext(app: $app, authenticated: $authenticated, signedRequest: $signedRequest, user: $user)"
    }
    // PRIVATE

    private String buildFacebookURL(path = "", parameters = [:]) {
        String url = "https://www.facebook.com/"
        if (path) {
            if (path[0] == "/") {
                path = path.substring(1)
            }
            url += path
        }
        if (parameters) {
            url += "?" + serializeQueryString(parameters)
        }
        return url
    }

    private def getConfig() {
        grailsApplication.config.grails.plugin.facebooksdk
    }

    private String getCurrentURL(String queryString = '') {
        Map params = request.params.findAll { key, value -> !DROP_QUERY_PARAMS.contains(key) }
        String linkUrl = grailsLinkGenerator.link(
                absolute: true,
                action: request.params.action,
                controller: request.params.controller,
                params: params
        )
        if (request.currentRequest.getHeader('X-Forwarded-Proto')) {
            // Detect forwarded protocol (for example from EC2 Load Balancer)
            linkUrl.replace(new URL(linkUrl).protocol, request.currentRequest.getHeader('X-Forwarded-Proto'))
        }
        return linkUrl
    }

    private GrailsWebRequest getRequest() {
        return RequestContextHolder.getRequestAttributes()
    }

    private String serializeQueryString(Map parameters, boolean urlEncoded = true) {
        if (urlEncoded) {
            return parameters.collect {key, value -> key.toLowerCase().encodeAsURL() + '=' + value.encodeAsURL() }.join('&')
        } else {
            return parameters.collect {key, value -> key.toLowerCase().encodeAsURL() + '=' + value }.join('&')
        }
    }
}
