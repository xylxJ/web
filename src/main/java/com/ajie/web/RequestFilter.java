package com.ajie.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ajie.chilli.cache.redis.RedisClient;
import com.ajie.chilli.common.ResponseResult;
import com.ajie.chilli.utils.HtmlFilter;
import com.ajie.chilli.utils.common.JsonUtils;
import com.ajie.chilli.utils.common.StringUtils;
import com.ajie.chilli.utils.common.URLUtil;
import com.ajie.dao.pojo.TbUser;
import com.ajie.sso.role.RoleUtils;
import com.ajie.sso.user.UserService;

/**
 * 对请求进行拦截处理
 * 
 * @author niezhenjie
 */
public class RequestFilter implements Filter {

	private static final Logger logger = LoggerFactory
			.getLogger(RequestFilter.class);

	/** 忽略模式，对配置的请求忽略拦截 */
	protected static final String FILTER_MODE_IGNORE = "ignore";

	/** 拦截模式，对配置的请求进行拦截 */
	protected static final String FILTER_MODE_INTERCEPT = "intercept";

	/** 登录模式 -- 本系统登录 */
	protected static final String LOGIN_MODE_NATIVE = "native";

	/** 登录模式 -- 跳转到sso系统登录 */
	protected static final String LOGIN_MODE_SSO = "sso";

	/** 登录过期状态码 */
	static final int CODE_SESSION_INVALID = 400;
	/** 禁止访问状态码 */
	static final int CODE_FORBIDDEN = 403;

	/** 本地服务器id */
	protected static final int NATIVE_SERVICEID = 0XFF;

	/** 请求类型，头部标识 */
	protected static final String HEADER_MARK = "HDMK";
	/** 请求类型--小程序类型 */
	protected static final String HEADER_MINIPROGRAM = "MINIPGRAM";

	/** 本地走代理url标记，如http://www.ajie18.top/ajie/xxx */
	protected static final String NATIVE_PROXY_MARK = "ajie";

	/** 验证模式 —— 拦截或忽略 */
	protected String mode;

	/** 对此类uri进行拦截/忽略验证 */
	protected List<String> uriList;

	/** 登录模式，本系统自行处理还是跳到sso系统登录 */
	protected String loginMode;

	/** oss系统路径 */
	protected String ssoHost;

	/** 编码 */
	protected String encoding;

	/** 远程用户服务接口 */
	protected UserService userService;

	/** 是否对拦截的链接进行权限判断 */
	protected boolean checkRight;

	protected static final String REDIS_PREFIX = "ACCESS-";

	protected RedisClient redis;

	/** 选项 */
	protected int mark;

	/** 全局开启防xss攻击 */
	public static final int MARK_XSSDEFENSE = 1 << 0;

	public UserService getUserService() {
		return userService;
	}

	public void setUserService(UserService userService) {
		this.userService = userService;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public String getLoginMode() {
		return loginMode;
	}

	public void setLoginMode(String loginMode) {
		this.loginMode = loginMode;
	}

	public void setSsoHost(String ossHost) {
		this.ssoHost = ossHost;
	}

	public String getSsoHost() {
		return ssoHost;
	}

	public List<String> getUriList() {
		return uriList;
	}

	public void setUriList(List<String> uriList) {
		this.uriList = uriList;
	}

	public String getEncoding() {
		return encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	public void setCheckRight(boolean b) {
		this.checkRight = b;
	}

	public boolean getCheckRight() {
		return checkRight;
	}

	public void setRedisClient(RedisClient client) {
		this.redis = client;
	}

	public RedisClient getRedisClient() {
		return redis;
	}

	public void setXssOpen(boolean b) {
		if (b) {
			mark |= MARK_XSSDEFENSE;
		} else {
			mark &= ~MARK_XSSDEFENSE;
		}
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {

	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		try {
			HttpServletRequest req = (HttpServletRequest) request;
			HttpServletResponse res = (HttpServletResponse) response;
			request.setCharacterEncoding(null == encoding ? "utf-8" : encoding);
			if (checkPath(req, res, chain)) {
				return;
			}
			if (checkUser(req, res, chain)) {
				return;
			}
			chain.doFilter(getRequest(req), response);
		} catch (Throwable e) {
			// 全局异常捕捉，处理运行时异常
			logger.error("", e);
			throw e;
		}
	}

	/**
	 * 检验路径
	 * 
	 * @param request
	 * @param response
	 * @throws Throwable
	 * @return 进入doFilter则返回true
	 */
	boolean checkPath(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		String uri = request.getRequestURI();
		// 配置不拦截路径检验模式
		if (StringUtils.eq(FILTER_MODE_IGNORE, mode)) {
			// 不拦截的路径，直接过
			if (URLUtil.matchs(uriList, uri)) {
				chain.doFilter(request, response);
				return true;
			}
		}
		// 拦截路径校验模式
		if ((StringUtils.eq(FILTER_MODE_INTERCEPT, mode))) {
			if (!URLUtil.matchs(uriList, uri)) {
				chain.doFilter(getRequest(request), response);
				return true;
			}
		}
		return false;
	}

	/**
	 * 
	 * @param request
	 * @param response
	 * @param chain
	 * @return true表示处理完毕，可以直接返回，调用者不需要在继续往下执行
	 * @throws IOException
	 */
	boolean checkUser(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws IOException {
		String uri = request.getRequestURI();
		// 需要验证用户是否登录
		TbUser user = userService.getUser(request);
		String ajaxHeader = request.getHeader("X-Requested-With");// ajax请求特有的请求头
		if (null == user) {
			if ("XMLHttpRequest".equals(ajaxHeader)) {
				// 只适用于ajax请求
				ResponseResult ret = ResponseResult.newResult(
						CODE_SESSION_INVALID, "session is invalid");
				send_aj(response, ret);
				return true;
			}
			gotoLogin(getRequest(request), response);
			return true;
		}
		if (checkRight) {
			boolean right = RoleUtils.checkRole(user, uri);
			if (!right) {
				if (logger.isDebugEnabled()) {
					logger.debug(user.toString() + " 无访问权限: " + uri);
				}
				if ("XMLHttpRequest".equals(ajaxHeader)) {
					// 只适用于ajax请求
					ResponseResult ret = ResponseResult.newResult(
							CODE_FORBIDDEN, "无权限");
					send_aj(response, ret);
					return true;
				}
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
				return true;
			}
		}
		return false;
	}

	/**
	 * aj请求时的返回数据
	 * 
	 * @param response
	 * @param ret
	 * @throws IOException
	 */
	void send_aj(HttpServletResponse response, ResponseResult ret)
			throws IOException {
		PrintWriter writer = response.getWriter();
		writer.write(JsonUtils.toJSONString(ret));
		writer.flush();
		writer.close();
	}

	private HttpServletRequest getRequest(HttpServletRequest req) {
		if ((mark & MARK_XSSDEFENSE) == MARK_XSSDEFENSE) {
			return getProxyRequest(req);
		}
		return req;
	}

	@Override
	public void destroy() {

	}

	/**
	 * 在请求中加入服务器id，以做区别
	 * 
	 * @param request
	 */
	/*
	 * private void handleServiceIdentify(HttpServletRequest request) { //
	 * 只有通过代理的请求，才回有该头部 String ip = request.getHeader("X-Real-IP"); int sid = 0;
	 * if (StringUtils.isEmpty(serverId)) { // 本机，但通过了代理，默认0xff if (null != ip)
	 * sid = NATIVE_SERVICEID; } else { sid = Integer.valueOf(serverId); } if
	 * (sid == 0) { // 本机并且没有通过代理，空吧，如果serviceId不为空，则需要设置
	 * request.setAttribute("serverId", ""); } else {
	 * request.setAttribute("serverId", Toolkits.deci2Hex(sid, "x")); }
	 * 
	 * }
	 */

	/**
	 * 跳到sso系统进行登录
	 * 
	 * @param req
	 * @param res
	 */
	void gotoLogin(HttpServletRequest req, HttpServletResponse res) {
		// 拿到前端访问的host,注意Host头需要在前端代理服务器上配置
		// 如果不配置，则host拿到的是前端代理转发的链接，而且这链接会带端口
		// 协议
		String protocol = req.getProtocol();
		if (null == protocol) {
			// 理论上不可能吧？？？
			protocol = "http";
		}
		if (protocol.toLowerCase().startsWith("https")) {
			protocol = "https";
		} else {
			protocol = "http";
		}
		// 主机名部分
		String host = req.getHeader("Host");
		// uri经过了代理可能会发生变化，所以需要从头部里取出，nginx设置proxy_set_header uri $request_uri;
		String uri = req.getHeader("uri");
		if (null == uri) {
			// uri部分
			uri = req.getRequestURI();
		}
		// 参数部分
		String query = req.getQueryString();
		String ref = "";
		ref = protocol + "://" + host + uri;
		if (!StringUtils.isEmpty(query)) {// 有带参
			try {
				// %3f解码后是?
				ref += "%3f" + URLEncoder.encode(query, "utf-8");
			} catch (UnsupportedEncodingException e) {
				logger.warn("不支持utf-8字符编码转换" + query);
			}
		}
		String ssoHost = getSsoHost();
		if (!ssoHost.endsWith("/")) {
			ssoHost += "/";
		}
		try {
			res.sendRedirect(ssoHost + "login?ref=" + ref);
		} catch (IOException e) {
			logger.error("跳转到oss登录页面失败");
		}
	}

	/**
	 * 动态代理
	 * 
	 * @param request
	 * @return
	 */
	private HttpServletRequest getProxyRequest(final HttpServletRequest request) {
		return (HttpServletRequest) Proxy.newProxyInstance(this.getClass()
				.getClassLoader(), request.getClass().getInterfaces(),
				new InvocationHandler() {

					@Override
					public Object invoke(Object proxy, Method method,
							Object[] args) throws Throwable {
						if ("getParameter".equals(method.getName())) {
							String origin = (String) method.invoke(request,
									args);
							if (StringUtils.isEmpty(origin)) {
								return origin;
							}
							return HtmlFilter.escape(origin);
						} else {
							return method.invoke(request, args);
						}
					}
				});
	}

	public static void main(String[] args) {
	}

}
