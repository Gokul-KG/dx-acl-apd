package iudx.apd.acl.server.notification.service;

import io.vertx.sqlclient.Pool;
import static iudx.apd.acl.server.apiserver.util.Constants.*;
import static iudx.apd.acl.server.notification.util.Constants.GET_CONSUMER_NOTIFICATION_QUERY;
import static iudx.apd.acl.server.notification.util.Constants.GET_PROVIDER_NOTIFICATION_QUERY;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import iudx.apd.acl.server.apiserver.util.Role;
import iudx.apd.acl.server.apiserver.util.User;
import iudx.apd.acl.server.common.HttpStatusCode;
import iudx.apd.acl.server.common.ResponseUrn;
import iudx.apd.acl.server.database.PostgresService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetNotification {
  private static final Logger LOG = LoggerFactory.getLogger(GetNotification.class);
  private static final String FAILURE_MESSAGE = "Notifications could not be fetched";
  private final PostgresService postgresService;

  public GetNotification(PostgresService postgresService) {
    this.postgresService = postgresService;
  }

  /**
   * Fetches notifications based on the user role
   *
   * @param user Detail about the user
   * @return Notification or Failure with type Future JsonObject
   */
  public Future<JsonObject> initiateGetNotifications(User user) {
    Role role = user.getUserRole();
    switch (role) {
      case CONSUMER_DELEGATE:
      case CONSUMER:
        return getUserNotification(user, GET_CONSUMER_NOTIFICATION_QUERY, Role.CONSUMER);
      case PROVIDER_DELEGATE:
      default:
        return getUserNotification(user, GET_PROVIDER_NOTIFICATION_QUERY, Role.PROVIDER);
    }
  }

  /**
   * Fetches the notifications concerned with the respective consumer when the user requesting it is
   * a consumer or consumer delegate
   *
   * @param user Information about the user
   * @param query A SELECT query to fetch details
   * @return notifications from the consumer or for the provider as success response <br>
   *     or failure response both of type Future JsonObject
   */
  public Future<JsonObject> getUserNotification(User user, String query, Role role) {
    LOG.trace("inside getUserNotification method");
    Promise<JsonObject> promise = Promise.promise();
    UUID userId = UUID.fromString(user.getUserId());
    String resourceServerUrl = user.getResourceServerUrl();
    LOG.trace(user.toString());
    Tuple tuple = Tuple.of(userId, resourceServerUrl);
    JsonObject jsonObject =
        new JsonObject()
            .put("email", user.getEmailId())
            .put(
                "name",
                new JsonObject()
                    .put("firstName", user.getFirstName())
                    .put("lastName", user.getLastName()))
            .put("id", user.getUserId());
    JsonObject userInfo = new JsonObject().put(RS_SERVER_URL, user.getResourceServerUrl());
    if (role.equals(Role.CONSUMER)) {
      userInfo.put("consumer", jsonObject);
    } else {
      userInfo.put("provider", jsonObject);
    }

    this.executeGetNotification(tuple, query, userInfo, role)
        .onComplete(
            handler -> {
              if (handler.succeeded()) {
                LOG.info("success while executing GET user request");
                promise.complete(handler.result());
              } else {
                LOG.error("Failure while executing GET user request");
                promise.fail(handler.cause().getMessage());
              }
            });
    return promise.future();
  }

  /**
   * Executes GET notification query based on the role of the user
   *
   * @param tuple replaceable in the query with type Tuple
   * @param query to be executed
   * @param information to be merged with the response
   * @param role user role
   * @return response returned from the query execution
   */
  public Future<JsonObject> executeGetNotification(
      Tuple tuple, String query, JsonObject information, Role role) {
    Promise<JsonObject> promise = Promise.promise();
    Collector<Row, ?, List<JsonObject>> rowListCollector =
        Collectors.mapping(row -> row.toJson(), Collectors.toList());
    Pool pool = postgresService.getPool();
    pool.withConnection(
            sqlConnection ->
                sqlConnection
                    .preparedQuery(query)
                    .collecting(rowListCollector)
                    .execute(tuple)
                    .map(rows -> rows.value()))
        .onComplete(
            handler -> {
              if (handler.succeeded()) {
                if (!handler.result().isEmpty()) {
                  for (JsonObject jsonObject : handler.result()) {
                    jsonObject.mergeIn(information).mergeIn(getInformation(jsonObject, role));
                  }
                  JsonObject result =
                      new JsonObject()
                          .put(TYPE, ResponseUrn.SUCCESS_URN.getUrn())
                          .put(TITLE, ResponseUrn.SUCCESS_URN.getMessage())
                          .put(RESULT, handler.result());

                  promise.complete(
                      new JsonObject()
                          .put(RESULT, result)
                          .put(STATUS_CODE, HttpStatusCode.SUCCESS.getValue()));
                } else {
                  JsonObject response =
                      new JsonObject()
                          .put(TYPE, HttpStatusCode.NOT_FOUND.getValue())
                          .put(TITLE, ResponseUrn.RESOURCE_NOT_FOUND_URN.getUrn())
                          .put(
                              DETAIL,
                              "Access request not found, for the server : "
                                  + information.getString(RS_SERVER_URL));
                  LOG.error(
                      "No Request found for the resource server: {}",
                      information.getString(RS_SERVER_URL));
                  promise.fail(response.encode());
                }
              } else {
                JsonObject response =
                    new JsonObject()
                        .put(TYPE, HttpStatusCode.INTERNAL_SERVER_ERROR.getValue())
                        .put(TITLE, ResponseUrn.DB_ERROR_URN.getUrn())
                        .put(DETAIL, FAILURE_MESSAGE + ", Failure while executing query");
                promise.fail(response.encode());
                LOG.error("Error response : {}", handler.cause().getMessage());
              }
            });
    return promise.future();
  }

  public JsonObject getInformation(JsonObject jsonObject, Role role) {
    if (role.equals(Role.CONSUMER)) {
      return getConsumerInformation(jsonObject);
    }
    return getProviderInformation(jsonObject);
  }

  public JsonObject getConsumerInformation(JsonObject jsonObject) {
    String ownerFirstName = jsonObject.getString("ownerFirstName");
    String ownerLastName = jsonObject.getString("ownerLastName");
    String ownerId = jsonObject.getString("ownerId");
    String ownerEmail = jsonObject.getString("ownerEmailId");
    JsonObject providerJson =
        new JsonObject()
            .put("email", ownerEmail)
            .put(
                "name",
                new JsonObject().put("firstName", ownerFirstName).put("lastName", ownerLastName))
            .put("id", ownerId);
    final JsonObject providerInfo = new JsonObject().put("provider", providerJson);
    jsonObject.remove("ownerFirstName");
    jsonObject.remove("ownerLastName");
    jsonObject.remove("ownerId");
    jsonObject.remove("consumerId");
    jsonObject.remove("ownerEmailId");
    return providerInfo;
  }

  public JsonObject getProviderInformation(JsonObject jsonObject) {
    String consumerFirstName = jsonObject.getString("consumerFirstName");
    String consumerLastName = jsonObject.getString("consumerLastName");
    String consumerId = jsonObject.getString("consumerId");
    String consumerEmail = jsonObject.getString("consumerEmailId");
    JsonObject consumerJson =
        new JsonObject()
            .put("email", consumerEmail)
            .put(
                "name",
                new JsonObject()
                    .put("firstName", consumerFirstName)
                    .put("lastName", consumerLastName))
            .put("id", consumerId);
    final JsonObject consumerInfo = new JsonObject().put("consumer", consumerJson);
    jsonObject.remove("consumerFirstName");
    jsonObject.remove("consumerLastName");
    jsonObject.remove("consumerId");
    jsonObject.remove("consumerEmailId");
    jsonObject.remove("ownerId");
    return consumerInfo;
  }
}
