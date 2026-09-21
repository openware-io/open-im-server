package com.gvchat.protocol.mq.topic;

public final class ImMqTopics {
  public static final String MESSAGE_SEND_COMMAND = "im_message_command_send_v1";
  public static final String MESSAGE_READ_COMMAND = "im_message_command_read_v1";
  public static final String MESSAGE_RECALL_COMMAND = "im_message_command_recall_v1";
  public static final String MESSAGE_STORED_EVENT = "im_message_event_stored_v1";
  public static final String MESSAGE_RECALLED_EVENT = "im_message_event_recalled_v1";
  public static final String MESSAGE_EDITED_EVENT = "im_message_event_edited_v1";
  public static final String MESSAGE_CHAT_CLEARED_EVENT = "im_message_event_chat_cleared_v1";
  public static final String SECRET_MESSAGE_STORED_EVENT = "im_message_event_secret_stored_v1";
  public static final String SECRET_GROUP_MESSAGE_STORED_EVENT = "im_message_event_secret_group_stored_v1";
  public static final String SECRET_MESSAGE_DESTROYED_EVENT = "im_message_event_secret_destroyed_v1";
  public static final String SECRET_MESSAGE_DESTROY_COMMAND = "im_message_command_secret_destroy_v1";
  public static final String FRIEND_REQUESTED_EVENT = "im_user_event_friend_requested_v1";
  public static final String FRIEND_ACCEPTED_EVENT = "im_user_event_friend_accepted_v1";
  public static final String USER_STATUS_CHANGED_EVENT = "im_user_event_status_changed_v1";
  public static final String USER_AUTHENTICATION_INVALIDATED_EVENT = "im_user_event_authentication_invalidated_v1";
  public static final String USER_CHAT_RECORDS_PURGE_EVENT = "im_user_event_chat_records_purged_v1";
  public static final String USER_DATA_WIPE_REQUESTED_EVENT = "im_user_event_data_wipe_requested_v1";
  public static final String USER_PROFILE_CHANGED_EVENT = "im_user_event_profile_changed_v1";
  public static final String CONVERSATION_AUTHORIZATION_CHANGED_EVENT = "im_conversation_event_authorization_changed_v1";
  public static final String SECRET_CHAT_CREATED_EVENT = "im_conversation_event_secret_chat_created_v1";
  public static final String SECRET_CHAT_DELETED_EVENT = "im_conversation_event_secret_chat_deleted_v1";
  public static final String SECRET_CHAT_DESTROY_POLICY_CHANGED_EVENT = "im_conversation_event_secret_chat_destroy_policy_changed_v1";

  private ImMqTopics() {
  }
}
