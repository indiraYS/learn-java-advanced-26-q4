# Getting Started

### Requirements
```
* java version >= 25
* rancher or docker machine
* mvn version >= 3.9.11
```

### Instruction to run the App locally
1. run docker-compose file

```
docker run -d -p 8081:8080 \
  -e JSON_CONFIG='{
  "interactiveLogin": true,
  "httpServer": "NettyWrapper",
  "tokenCallbacks": [
    {
      "issuerId": "issuer-a",
      "tokenExpiry": 300,
      "requestMappings": [
        {
          "requestParam": "role",
          "match": "ADMIN",
          "claims": {
            "role": "ADMIN"
          }
        },
        {
          "requestParam": "role",
          "match": "USER",
          "claims": {
            "role": "USER"
          }
        }
      ]
    }
  ]
}'  ghcr.io/navikt/mock-oauth2-server:2.1.0
```

2. compile
   ```mvn clean package```
3.  run applicaiton
```
mvn spring-boot:run -Dspring-boot.run.profiles=local
```
#### <i>Optional: run Postgres container, for the `dev` profile</i>
```docker run --name my-postgres17 \                             
  -e POSTGRES_USER=my_custom_user \
  -e POSTGRES_PASSWORD=mysecretpassword \
  -e POSTGRES_DB=my_new_database \
  -p 5432:5432 \
  -v "$(pwd)/development/pgdata:/var/lib/postgresql/data" \
  -d postgres:17-alpine```

### Instruction how to test this app using curl commands
1. check that application responds to http requests
```
curl -X GET http://localhost:8081/actuator/health
```
2. create a jwt token 
```
curl -X POST http://localhost:8081/issuer-a/token \
-H "Content-Type: application/x-www-form-urlencoded" \
-d "grant_type=client_credentials" \
-d "client_id=YOUR_CLIENT_ID" \
-d "client_secret=YOUR_CLIENT_SECRET" \
-d "scope=openid" \
-d "role=ADMIN" ## options: USER, ADMIN
```
 this is common response
```
{
"token_type" : "Bearer",
"access_token" : "eyJraWQiOiJpc3N1ZXItYSIsInR5cCI6IkpXVCIsImFsZyI6IlJTMjU2In0.eyJuYmYiOjE3OTMzOTA5NjEsInJvbGUiOiJBRE1JTiIsImlzcyI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MS9pc3N1ZXItYSIsImV4cCI6MTc5MzM5MTA4MSwiaWF0IjoxNzkzMzkwOTYxLCJqdGkiOiIxZDA1YzZlZi1hZmI1LTRlYzctOTQyNS05NzdiZjI0NjEzM2YifQ.SNNWU0bhNgIi5liOZRf1p2EV4ZSGMq_XV0dKFT3p6M7ZDfKra1_oURc_R7OWrOoRoeoF8nkvSnz9eBQLtD0A8dTYfPLyuWTo0zRlgbXXBFcROoyLmBjJ1l7Asp11Pbz0spFMdm_FA_maOy_-IaWzYgHiI4gsFGBGCs_0k0QSrFuwOezcMb1MZBK4E3kmUFu-FYkwBDk7U8NFemk29SydUmxq03Pa1S_DGLW_gvr7G52rzn5ascWr2xLQoy_PF5G_hR-ahfJoHraR2AuLLSxDJugOqdqHe1hd5ZlUasJYkohN7z9LyWxlHfxw-AbgXZ0vkQI23IIlB4pA3zig4TaMpg",
"expires_in" : 119,
"scope" : "openid"
}
```

decoded token expected to look like
```
{
"nbf": 1793387902,
"role": "ADMIN",
"iss": "http://localhost:8081/issuer-a",
"exp": 1793388022,
"iat": 1793387902,
"jti": "a0abe5f8-aede-43b0-b3f2-8fe01d2670a6"
}
```

3. use this curl command to get all existing customers

```
curl -X GET http://localhost:8080/api/v1/customers -H "Authorization: Bearer <your_access_token>"
```

3. use this curl command to add a new customer

```
curl -X POST http://localhost:8080/api/v1/customers -H "Authorization: Bearer <your_access_token> -d "{\"name\":\"Ivan\", \"email\":\"ivan@email.com\"}" -ik
```