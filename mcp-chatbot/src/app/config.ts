import { AuthConfig } from 'angular-oauth2-oidc';

/** URL of the mcp-client deep-chat streaming (Server-Sent Events) endpoint. */
export const MCP_CHAT_URL = 'http://localhost:8083/api/chat/stream';

/** Endpoint used to approve/deny a pending MCP tool call. */
export const MCP_APPROVE_URL = 'http://localhost:8083/api/chat/approve';

/** Endpoint used to answer a pending temperature-unit elicitation. */
export const MCP_ELICITATION_DECISION_URL = 'http://localhost:8083/api/chat/elicitation-decision';

/** OpenID Connect configuration pointing at the mcp-authorization-server. */
export const authConfig: AuthConfig = {
  // Spring Authorization Server issuer (must match the discovery document).
  issuer: 'http://localhost:9000',
  // The SPA is redirected back here after a successful login.
  redirectUri: window.location.origin,
  postLogoutRedirectUri: window.location.origin,
  // Public client registered in the authorization server (PKCE, no secret).
  clientId: 'mcp-chatbot-client',
  responseType: 'code',
  scope: 'openid profile mcp.tools mcp.tools.movies mcp.tools.weather mcp.tools.diagnostics',
  // Local demo runs over http.
  requireHttps: false,
  strictDiscoveryDocumentValidation: false,
  showDebugInformation: true,
};

