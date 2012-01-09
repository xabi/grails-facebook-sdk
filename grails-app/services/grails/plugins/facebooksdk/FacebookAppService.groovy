package grails.plugins.facebooksdk

import grails.converters.JSON

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.servlet.http.Cookie

import org.apache.commons.codec.binary.Base64

import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.web.context.request.RequestContextHolder

import com.restfb.exception.FacebookOAuthException

class FacebookAppService {
	
	final static List DROP_QUERY_PARAMS = ["code","state","signed_request"]
	final static String VERSION = "3.1.1"
	
	boolean transactional = false
	
	Long appId = 0
	String appPermissions = ""
	String appSecret = ""
	def facebookAppCookieScope
	def facebookAppPersistentScope // Any persistentScope class with the following methods : deleteData, deleteAllData, getData, isEnabled, setData
	def facebookAppRequestScope
	
	GrailsWebRequest getRequest() {
		return RequestContextHolder.getRequestAttributes()
	}

	/*
	* @description Invalidate current user (persistent data and cookie)
	* @hint
	*/
	void invalidateUser() {
		facebookAppRequestScope.deleteData("access_token")
		facebookAppRequestScope.deleteData("user_id")
		if (facebookAppCookieScope.hasCookie()) {
			facebookAppCookieScope.deleteCookie()
		}
		if (facebookAppPersistentScope.isEnabled()) {
			facebookAppPersistentScope.deleteAllData()
		}
	}
	
	/*
	* @description Get OAuth accessToken
	* @hint Determines the access token that should be used for API calls. The first time this is called, accessToken is set equal to either a valid user access token, or it"s set to the application access token if a valid user access token wasn"t available. Subsequent calls return whatever the first call returned.
	*/
	String getAccessToken() {
		if (!facebookAppRequestScope.hasData("access_token")) {
			String accessToken = getUserAccessToken()
			if (!accessToken) {
				// No user access token, establish access token to be the application access token, in case we navigate to the /oauth/access_token endpoint, where SOME access token is required.
				accessToken = getApplicationAccessToken()
			}
			facebookAppRequestScope.setData("access_token", accessToken)
		}
		return facebookAppRequestScope.getData("access_token")
	}
	
	/*
	* @description Get application OAuth accessToken
	* @hint
	*/
	String getApplicationAccessToken(Boolean oauthEnabled = false) {
		String accessToken = ""
		if (oauthEnabled) {
			def facebookGraphClient = new FacebookGraphClient()
			Map parameters = [client_id:this.appId,
							client_secret:this.appSecret,
							grant_type:"client_credentials"]
			def result = facebookGraphClient.fetchObject("oauth/access_token", parameters)
			if (result["access_token"]) accessToken = result["access_token"]
		} else {
			accessToken = this.appId + "|" + this.appSecret
		}
		return accessToken
	}
	
	/*
	* @description Get a login status URL to fetch the status from facebook.
	* @hint
	* Available parameters:
	* - ok_session: the URL to go to if a session is found
	* - no_session: the URL to go to if the user is not connected
	* - no_user: the URL to go to if the user is not signed into facebook
		 */
	String getLoginStatusURL(Map parameters = [:]) {
		if (!request.params["api_key"]) parameters["api_key"] = this.appId
		if (!request.params["no_session"]) parameters["no_session"] = getCurrentURL()
		if (!request.params["no_user"]) parameters["no_user"] = getCurrentURL()
		if (!request.params["ok_session"]) parameters["ok_session"] = getCurrentURL()
		if (!request.params["session_version"]) parameters["session_version"] =3
		return getURL("extern/login_status.php", parameters)
	}
	 
	 /*
	* @description Get a Login URL for use with redirects.
	* @hint By default, full page redirect is assumed. If you are using the generated URL with a window.open() call in JavaScript, you can pass in display=popup as part of the parameters.
	* Available parameters:
		 * - redirect_uri: the url to go to after a successful login
		 * - scope: comma separated list of requested extended perms
	*/
	String getLoginURL(Map parameters = [:]) {
		establishCSRFStateToken()
		if (!parameters["client_id"]) parameters["client_id"] = this.appId
		if (!parameters["redirect_uri"]) parameters["redirect_uri"] = getCurrentURL()
		if (!parameters["state"]) parameters["state"] = getCSRFStateToken()
		return getURL("dialog/oauth", parameters)
	}
	 
	 /*
	* @description Get a Logout URL suitable for use with redirects.
	* @hint
	* Available parameters:
		 * - next: the url to go to after a successful logout
	*/
	String getLogoutURL(Map parameters = [:]) {
		if (!parameters["access_token"]) parameters["access_token"] = getUserAccessToken()
		if (!parameters["next"]) parameters["next"] = getCurrentURL()
		return getURL("logout.php", parameters)
	}
	
	/*
	* @description Get user OAuth accessToken
	* @hint Determines and returns the user access token, first using the signed request if present, and then falling back on the authorization code if present.	The intent is to return a valid user access token, or " if one is determined to not be available.
	*/
	String getUserAccessToken() {
		String accessToken = ""
		// First, consider a signed request if it"s supplied. if there is a signed request, then it alone determines the access token.
		Map signedRequest = getSignedRequestData()
		if (signedRequest) {
			if (signedRequest["oauth_token"]) {
				// apps.facebook.com hands the access_token in the signed_request
				accessToken = signedRequest["oauth_token"]
				facebookAppPersistentScope.setData("access_token", accessToken)
			} else if (signedRequest["code"]) {
				// Facebook Javascript SDK puts an authorization code in signed request
				if (signedRequest["code"] == facebookAppPersistentScope.getData("code")) {
					accessToken = facebookAppPersistentScope.getData("access_token")
				} else {
					accessToken = getAccessTokenFromCode(signedRequest["code"], "")
					if (accessToken) {
						facebookAppPersistentScope.setData("code", signedRequest["code"])
						facebookAppPersistentScope.setData("access_token", accessToken)
					}
				}
			}
			
			if (!accessToken) {
				// Signed request states there"s no access token, so anything stored should be invalidated.
				invalidateUser()
			}
		} else {
			// Falling back on the authorization code if present
			String code = getAuthorizationCode()
			if (code && code != facebookAppPersistentScope.getData("code")) {
				accessToken = getAccessTokenFromCode(code)
				if (accessToken) {
					facebookAppPersistentScope.setData("code", code)
					facebookAppPersistentScope.setData("access_token", accessToken)
				} else {
					// Code was bogus, so everything based on it should be invalidated.
					invalidateUser()
				}
			} else {
				// Falling back on persistent store, knowing nothing explicit (signed request, authorization code, etc.) was present to shadow it (or we saw a code in URL/FORM scope, but it"s the same as what"s in the persistent store)
				accessToken = facebookAppPersistentScope.getData("access_token")
				if (!accessToken) {
					// Invalid session, so everything based on it should be invalidated.
					invalidateUser()
				}
			}
		}
		return accessToken
	}
	
	/*
	* @description Get the UID of the connected user, or 0 if the Facebook user is not connected.
	* @hint Determines the connected user by first examining any signed requests, then considering an authorization code, and then falling back to any persistent store storing the user.
	*/
	Long getUserId() {
		if (!facebookAppRequestScope.hasData("user_id")) {
			Long userId = 0
			// If a signed request is supplied, then it solely determines who the user is.
			Map signedRequestData = getSignedRequestData()
			if (signedRequestData) {
				if (signedRequestData["user_id"]) {
					userId = signedRequestData["user_id"].toLong()
					facebookAppPersistentScope.setData("user_id", userId)
				} else {
					// If the signed request didn"t present a user id, then invalidate all entries in any persistent store.
					invalidateUser()
				}
			} else {
				userId = facebookAppPersistentScope.getData("user_id", 0)
				// Use access_token to fetch user id if we have a user access_token, or if the cached access token has changed.
				String accessToken = getAccessToken()
				if (accessToken && accessToken != getApplicationAccessToken() && !(userId > 0 && accessToken == facebookAppPersistentScope.getData("access_token"))) {
					def facebookGraphClient = new FacebookGraphClient(accessToken)
					def result = facebookGraphClient.fetchObject("me", [fields:"id"])
					if (result?.id) {
						userId = result.id
						facebookAppPersistentScope.setData("user_id", userId)
					} else {
						invalidateUser()
				 	}
				}
			}
			facebookAppRequestScope.setData("user_id", userId)
		}
		return facebookAppRequestScope.getData("user_id") ?: 0
	}
	
	// PRIVATE
	
	private void establishCSRFStateToken() {
		if (getCSRFStateToken() == "") {
			String stateToken = UUID.randomUUID().encodeAsMD5()
			facebookAppRequestScope.setData("state", stateToken)
			facebookAppPersistentScope.setData("state", stateToken)
		}
	}
	
	private String getAccessTokenFromCode(String code, String redirectUri = "") {
		String accessToken = ""
		if (code) {
			try {
				def facebookGraphClient = new FacebookGraphClient()
				Map parameters = [client_id:this.appId,
								client_secret:this.appSecret,
								code:code,
								redirect_uri:redirectUri.encodeAsURL()]
				def result = facebookGraphClient.fetchObject("oauth/access_token", parameters)
				if (result["access_token"]) accessToken = result["access_token"]
			} catch (FacebookOAuthException exception) {
				if (exception.message.find("Code was invalid or expired")) {
					invalidateUser()
				}
				throw exception
			}
		}
		return accessToken
	}
	
	private String getAuthorizationCode() {
		String code = ""
		if (request.params["code"] && request.params["state"]) {
			String stateToken = getCSRFStateToken()
			if (stateToken != "" && stateToken == request.params["state"]) {
				// CSRF state token has done its job, so delete it
				facebookAppRequestScope.deleteData("state")
				facebookAppPersistentScope.deleteData("state")
				code = request.params["code"]
			}
		}
		return code
	}
	
	private String getCSRFStateToken() {
		if (!facebookAppRequestScope.hasData("state")) {
			facebookAppRequestScope.setData("state", facebookAppPersistentScope.getData("state"))
		}
		return facebookAppRequestScope.getData("state")
	}
	
	private String getCurrentURL(String queryString = "") {
		String currentURL = request.getCurrentRequest().getRequestURL().toString()
		String currentQueryString = request.getCurrentRequest().getQueryString()
		if (currentQueryString) {
			List keyValue
			List keyValues = currentQueryString.tokenize("&")
			if (keyValues) {
				keyValues.each {
					keyValue = it.tokenize("=")
					if (!DROP_QUERY_PARAMS.contains(keyValue[0])) {
						if (!queryString) {
							queryString += "&"
						}
						queryString += it
					}
				}
			}	
		}
		if (queryString) {
			currentURL += "?" + queryString
		}
		if (request.getCurrentRequest().getHeader("X-Forwarded-Proto")) {
			// Detect forwarded protocol (for example from EC2 Load Balancer)
			URL url = new URL(currentURL)
			currentURL.replace(url.getProtocol(), request.getCurrentRequest().getHeader("X-Forwarded-Proto"))
		}
		return currentURL
	}
	
	private Map getSignedRequestData() {
		if (!facebookAppRequestScope.hasData("signed_request")) {
			if (request.params["signed_request"]) {
				// apps.facebook.com (default iframe page)
				facebookAppRequestScope.setData("signed_request", parseSignedRequest(request.params.signed_request, this.appSecret))
			} else if (facebookAppCookieScope.hasCookie()) {
				// Cookie created by Facebook Connect Javascript SDK
				facebookAppRequestScope.setData("signed_request", parseSignedRequest(facebookAppCookieScope.getData(), this.appSecret))
			}
		}
		return facebookAppRequestScope.getData("signed_request") ?: [:]
	}
	
	private String getURL(path = "", parameters = [:]) {
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

	private Map parseSignedRequest(String signedRequest, String appSecret) {
		String encodedParameters = signedRequest.trim().tokenize(".")[-1].replace('_', '/').replace('-', '+')
		String encodedSignature = signedRequest.trim().tokenize(".")[0].replace('_', '/').replace('-', '+')
		
		// Validate signature
		Mac hmacSha256 = Mac.getInstance("HmacSHA256")
		hmacSha256.init(new SecretKeySpec(appSecret.bytes, "HmacSHA256"))
		byte[] expectedSignature = hmacSha256.doFinal(encodedParameters.bytes)
		assert expectedSignature == encodedSignature.decodeBase64(), "Invalid signed request"

		// Decode parameters
		Map parameters = JSON.parse(new String(encodedParameters.decodeBase64()))
		assert parameters["algorithm"] == "HMAC-SHA256", "Unknown algorithm. Expected HMAC-SHA256"

		return parameters
	}

	private String serializeQueryString(Map parameters, Boolean urlEncoded = true) {
		if (urlEncoded) {
			return parameters.collect {key, value -> key.toLowerCase().encodeAsURL() + '=' + value.encodeAsURL() }.join('&')
		} else {
			return parameters.collect {key, value -> key.toLowerCase().encodeAsURL() + '=' + value }.join('&')
		}
	}

}

/**
* Base scope
*/
class FacebookAppScope {
	
	def appId = 0
	
	GrailsWebRequest getRequest() {
		return RequestContextHolder.getRequestAttributes()
	}
	
	private String getKeyVariableName(String key) {
		assert this.appId, "Facebook appId must be defined"
		return "fb_${this.appId}_${key}"
	}
	
}

/**
* Signed request cookie (set by Facebook Javascript SDK)
*/
class FacebookAppCookieScope extends FacebookAppScope {
	
	void deleteCookie() {
		Cookie cookie = getCookie()
		if (cookie) {
			cookie.setMaxAge(0)
			request.getCurrentResponse().addCookie(cookie)
		}
	}
	
	Cookie getCookie() {
		Cookie appCookie
		for (Cookie cookie in request.getCurrentRequest().getCookies()) {
			if (cookie.name == getAppCookieName()) {
				appCookie = cookie
				break
			}
		}
		return appCookie
	}
	
	String getData() {
		Cookie cookie = getCookie()
		if (cookie) {
			return cookie.value
		} else {
			return [:]
		}
	}
	
	Boolean hasCookie() {
		return getCookie() ? true : false
	}
	
	// PRIVATE

	private String getAppCookieName() {
		assert this.appId, "Facebook appId must be defined"
		return "fbsr_${this.appId}"
	}
	
}

/**
* Uses HTTP request attributes scope to cache data during the duration of the request.
*/
class FacebookAppRequestScope extends FacebookAppScope {
	
	final static List REQUEST_KEYS = ['access_token','code','state','user_id','signed_request']
	
	Boolean deleteData(String key) {
		assert REQUEST_KEYS.contains(key), "Unsupported key passed to deleteData"
		return request.getCurrentRequest().removeAttribute(getKeyVariableName(key))
	}
	
	def getData(String key, defaultValue = '') {
		assert REQUEST_KEYS.contains(key), "Unsupported key passed to getData"
		return request.getCurrentRequest().getAttribute(getKeyVariableName(key)) ?: defaultValue
	}
 
	Boolean hasData(String key) {
		request.getCurrentRequest().getAttribute(getKeyVariableName(key)) ? true : false
	}
		
	void setData(String key, value) {
		assert REQUEST_KEYS.contains(key), "Unsupported key passed to setData"
		request.getCurrentRequest().setAttribute(getKeyVariableName(key), value)
	}
	
}


/**
* Uses HTTP request session attributes scope to provide a primitive persistent store, but another subclass of FacebookApp --one that you implement-- might use a database, memcache, or an in-memory cache.
*/

class FacebookAppSessionScope extends FacebookAppScope {
	
	final static List PERSISTENT_KEYS = ['access_token','code','state','user_id']
	
	void deleteData(String key) {
		assert PERSISTENT_KEYS.contains(key), "Unsupported key passed to deleteData"
		request.session.removeAttribute(getKeyVariableName(key))
	}
 
	void deleteAllData() {
		PERSISTENT_KEYS.each {key ->
			deleteData(key)
		}
	}
	
	def getData(String key, defaultValue = "") {
		assert PERSISTENT_KEYS.contains(key), "Unsupported key passed to getData"
		return request.session.getAttribute(getKeyVariableName(key)) ?: defaultValue
	}
	
	Boolean hasData(String key) {
		return request.session.getAttribute(getKeyVariableName(key)) ? true : false
	}
	
	Boolean isEnabled() {
		return true
	}
		
	void setData(String key, value) {
		assert PERSISTENT_KEYS.contains(key), "Unsupported key passed to setData"
		request.session.setAttribute(getKeyVariableName(key), value)
	}
	
}