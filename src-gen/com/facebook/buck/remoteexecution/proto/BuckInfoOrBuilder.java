// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:BuckInfoOrBuilder.java.pb.meta")
public interface BuckInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.BuckInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The buck build id of the command starting the remote execution session.
   * </pre>
   *
   * <code>string build_id = 1;</code>
   */
  java.lang.String getBuildId();
  /**
   * <pre>
   * The buck build id of the command starting the remote execution session.
   * </pre>
   *
   * <code>string build_id = 1;</code>
   */
  com.google.protobuf.ByteString
      getBuildIdBytes();

  /**
   * <pre>
   * Name of the Build Rule that's being executed
   * </pre>
   *
   * <code>string rule_name = 2;</code>
   */
  java.lang.String getRuleName();
  /**
   * <pre>
   * Name of the Build Rule that's being executed
   * </pre>
   *
   * <code>string rule_name = 2;</code>
   */
  com.google.protobuf.ByteString
      getRuleNameBytes();
}
