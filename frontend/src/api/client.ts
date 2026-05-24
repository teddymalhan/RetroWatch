import createClient from "openapi-fetch";
import createQueryClient from "openapi-react-query";
import type { paths } from "./types";

/**
 * Base openapi-fetch client.
 * Used internally by React Query hooks.
 */
export const fetchClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_URL ?? "",
});

/**
 * React Query hooks generated from OpenAPI specification.
 *
 * Usage:
 *   const { data, isLoading, error } = $api.useQuery("get", "/api/hello");
 *   const mutation = $api.useMutation("post", "/api/protected/resource");
 *
 * For authenticated requests, set the token in fetchClient headers before use.
 */
export const $api = createQueryClient(fetchClient);

/**
 * Set authentication token for all subsequent API calls.
 * Call this when user logs in or token refreshes.
 *
 * Example with Clerk:
 *   const { getToken } = useAuth();
 *   useEffect(() => {
 *     getToken().then(token => {
 *       if (token) setAuthToken(token);
 *     });
 *   }, [getToken]);
 */
export function setAuthToken(token: string | null) {
  if (token) {
    fetchClient.use({
      onRequest({ request }) {
        request.headers.set("Authorization", `Bearer ${token}`);
        return request;
      },
    });
  }
}
