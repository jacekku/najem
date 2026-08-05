#!/usr/bin/env bash
#
# Prepares the local Keycloak realm so a person can actually log in, and prints
# the one value the application needs to know about them.
#
# NAJEM never sees a password. Keycloak is the identity provider and the only
# thing NAJEM takes from a token is `sub` — so the useful output of this script
# is that subject id, which is what --najem.bootstrap.operator-subject wants.
#
#   ./tools/seed-keycloak.sh                  # user demo / demo
#   KC_USER=anna KC_PASS=sekret ./tools/seed-keycloak.sh
#
# Local development only, and it refuses to run against anything else: it holds
# the realm's admin credentials, and a script that will happily point at a real
# deployment is one command away from seeding a user into it.

set -euo pipefail

KC="${KC:-http://localhost:8180}"
REALM="${KC_REALM:-najem}"
CLIENT="${KC_CLIENT:-najem-app}"
APP="${APP_URL:-http://localhost:8080}"
USERNAME="${KC_USER:-demo}"
PASSWORD="${KC_PASS:-demo}"

case "$KC" in
  http://localhost:*|http://127.0.0.1:*) ;;
  *) echo "seed-keycloak: refusing to run against $KC — local development only" >&2; exit 1 ;;
esac

command -v jq >/dev/null || { echo "seed-keycloak needs jq" >&2; exit 1; }

admin_token() {
  curl -sS -X POST "$KC/realms/master/protocol/openid-connect/token" \
    -d client_id=admin-cli -d grant_type=password \
    -d "username=${KC_ADMIN:-admin}" -d "password=${KC_ADMIN_PASSWORD:-admin}" \
    | jq -r '.access_token // empty'
}

TOK=$(admin_token)
[ -n "$TOK" ] || { echo "seed-keycloak: could not authenticate to $KC as admin" >&2; exit 1; }
api() { curl -sS -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" "$@"; }

# The realm and the client already exist in this compose stack, but a script that
# only works on a machine somebody prepared by hand is not a setup script.
if ! api "$KC/admin/realms/$REALM" | jq -e .realm >/dev/null 2>&1; then
  echo "realm ${REALM}…"
  api -X POST "$KC/admin/realms" -d "$(jq -nc --arg r "$REALM" '{realm:$r,enabled:true}')" >/dev/null
fi

CLIENT_UUID=$(api "$KC/admin/realms/$REALM/clients?clientId=$CLIENT" | jq -r '.[0].id // empty')
if [ -z "$CLIENT_UUID" ]; then
  echo "client ${CLIENT}…"
  # Public client with PKCE rather than confidential: a server-rendered app that
  # holds its session server-side gains nothing from a client secret here, and a
  # secret is a value somebody would eventually package. There is no secret to leak.
  api -X POST "$KC/admin/realms/$REALM/clients" -d "$(jq -nc --arg c "$CLIENT" --arg a "$APP" '{
      clientId:$c, enabled:true, publicClient:true, protocol:"openid-connect",
      standardFlowEnabled:true, directAccessGrantsEnabled:false,
      redirectUris:[($a + "/*")], webOrigins:[$a],
      attributes:{"pkce.code.challenge.method":"S256"}}')" >/dev/null
  CLIENT_UUID=$(api "$KC/admin/realms/$REALM/clients?clientId=$CLIENT" | jq -r '.[0].id')
fi

# Both of these were configured by hand first and then written down, which is the wrong order: a
# realm somebody prepared interactively is a realm nobody else can reproduce.
#
# PKCE is REQUIRED rather than merely offered. The client is public, so the authorization code is
# the only thing between a redirect and a session; PKCE is what stops a stolen code being spent.
api -X PUT "$KC/admin/realms/$REALM/clients/$CLIENT_UUID" \
  -d '{"attributes":{"pkce.code.challenge.method":"S256"},"directAccessGrantsEnabled":true}' >/dev/null

# Without this mapper Keycloak issues access tokens whose only audience is "account", and NAJEM
# rejects every one of them: the deployment looks configured, the browser login works, and every
# API call fails validation. Found by asking for a token and reading the claim rather than by
# trusting that a configured resource server means an acceptable token.
if ! api "$KC/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
     | jq -e --arg c "$CLIENT" '.[] | select(.config["included.client.audience"] == $c)' >/dev/null 2>&1; then
  echo "audience mapper…"
  api -X POST "$KC/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
    -d "$(jq -nc --arg c "$CLIENT" '{
        name:"najem-audience", protocol:"openid-connect", protocolMapper:"oidc-audience-mapper",
        config:{"included.client.audience":$c, "id.token.claim":"false", "access.token.claim":"true"}}')" \
    >/dev/null
fi

USER_ID=$(api "$KC/admin/realms/$REALM/users?username=$USERNAME&exact=true" | jq -r '.[0].id // empty')
if [ -z "$USER_ID" ]; then
  echo "user ${USERNAME}…"
  api -X POST "$KC/admin/realms/$REALM/users" -d "$(jq -nc --arg u "$USERNAME" '{
      username:$u, enabled:true, emailVerified:true,
      firstName:"Anna", lastName:"Kowalska", email:($u + "@example.com")}')" >/dev/null
  USER_ID=$(api "$KC/admin/realms/$REALM/users?username=$USERNAME&exact=true" | jq -r '.[0].id')
fi

api -X PUT "$KC/admin/realms/$REALM/users/$USER_ID/reset-password" \
  -d "$(jq -nc --arg p "$PASSWORD" '{type:"password", value:$p, temporary:false}')" >/dev/null

# Keycloak's user id IS the `sub` claim, and `sub` is the only thing NAJEM trusts
# a token for. Everything else about this person — their role, their agencies —
# comes from NAJEM's own projection (decision D1).
cat <<EOF

realm     $REALM
client    $CLIENT  (public, PKCE)
login     $USERNAME / $PASSWORD
subject   $USER_ID

Start the app so this person is the one who may create the first agency:

  --najem.bootstrap.operator-subject=$USER_ID \\
  --spring.security.oauth2.client.provider.keycloak.issuer-uri=$KC/realms/$REALM \\
  --spring.security.oauth2.resourceserver.jwt.issuer-uri=$KC/realms/$REALM \\
  --najem.security.audience=$CLIENT

Then seed the demo data as that same person:

  NAJEM_OPERATOR_SUBJECT=$USER_ID ./tools/seed-demo.sh
EOF
