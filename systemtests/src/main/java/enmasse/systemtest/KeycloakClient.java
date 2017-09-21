package enmasse.systemtest;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import javax.ws.rs.core.Response;
import java.util.Arrays;

public class KeycloakClient {
    private final Keycloak keycloak;

    public KeycloakClient(Endpoint endpoint, String adminUser, String adminPassword) {
        Logging.log.info("Logging into keycloak with {}/{}", adminUser, adminPassword);
        this.keycloak = Keycloak.getInstance(
                "http://" + endpoint.getHost() + ":" + endpoint.getPort() + "/auth",
                "master",
                adminUser,
                adminPassword,
                "admin-cli");
    }

    public void createUser(String realm, String userName, String password) {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername(userName);
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);
        userRep.setCredentials(Arrays.asList(cred));
        userRep.setEnabled(true);
        Response response = keycloak.realm(realm).users().create(userRep);
        if (response.getStatus() != 201) {
            throw new RuntimeException("Unable to create user: " + response);
        }
    }

    public void deleteUser(String realm, String userName) {
        keycloak.realm(realm).users().delete(userName);
    }
}
