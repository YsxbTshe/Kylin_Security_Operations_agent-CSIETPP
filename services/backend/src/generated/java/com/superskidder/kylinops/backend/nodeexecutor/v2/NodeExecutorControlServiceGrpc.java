package com.superskidder.kylinops.backend.nodeexecutor.v2;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class NodeExecutorControlServiceGrpc {

  private NodeExecutorControlServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "kylin.nodeexecutor.v2.NodeExecutorControlService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> getEnrollNodeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "EnrollNode",
      requestType = com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest.class,
      responseType = com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> getEnrollNodeMethod() {
    io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> getEnrollNodeMethod;
    if ((getEnrollNodeMethod = NodeExecutorControlServiceGrpc.getEnrollNodeMethod) == null) {
      synchronized (NodeExecutorControlServiceGrpc.class) {
        if ((getEnrollNodeMethod = NodeExecutorControlServiceGrpc.getEnrollNodeMethod) == null) {
          NodeExecutorControlServiceGrpc.getEnrollNodeMethod = getEnrollNodeMethod =
              io.grpc.MethodDescriptor.<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "EnrollNode"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeExecutorControlServiceMethodDescriptorSupplier("EnrollNode"))
              .build();
        }
      }
    }
    return getEnrollNodeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse> getControlStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ControlStream",
      requestType = com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest.class,
      responseType = com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse> getControlStreamMethod() {
    io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse> getControlStreamMethod;
    if ((getControlStreamMethod = NodeExecutorControlServiceGrpc.getControlStreamMethod) == null) {
      synchronized (NodeExecutorControlServiceGrpc.class) {
        if ((getControlStreamMethod = NodeExecutorControlServiceGrpc.getControlStreamMethod) == null) {
          NodeExecutorControlServiceGrpc.getControlStreamMethod = getControlStreamMethod =
              io.grpc.MethodDescriptor.<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ControlStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeExecutorControlServiceMethodDescriptorSupplier("ControlStream"))
              .build();
        }
      }
    }
    return getControlStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse> getArtifactStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ArtifactStream",
      requestType = com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest.class,
      responseType = com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest,
      com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse> getArtifactStreamMethod() {
    io.grpc.MethodDescriptor<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse> getArtifactStreamMethod;
    if ((getArtifactStreamMethod = NodeExecutorControlServiceGrpc.getArtifactStreamMethod) == null) {
      synchronized (NodeExecutorControlServiceGrpc.class) {
        if ((getArtifactStreamMethod = NodeExecutorControlServiceGrpc.getArtifactStreamMethod) == null) {
          NodeExecutorControlServiceGrpc.getArtifactStreamMethod = getArtifactStreamMethod =
              io.grpc.MethodDescriptor.<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ArtifactStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeExecutorControlServiceMethodDescriptorSupplier("ArtifactStream"))
              .build();
        }
      }
    }
    return getArtifactStreamMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NodeExecutorControlServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceStub>() {
        @java.lang.Override
        public NodeExecutorControlServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeExecutorControlServiceStub(channel, callOptions);
        }
      };
    return NodeExecutorControlServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static NodeExecutorControlServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceBlockingV2Stub>() {
        @java.lang.Override
        public NodeExecutorControlServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeExecutorControlServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return NodeExecutorControlServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NodeExecutorControlServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceBlockingStub>() {
        @java.lang.Override
        public NodeExecutorControlServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeExecutorControlServiceBlockingStub(channel, callOptions);
        }
      };
    return NodeExecutorControlServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NodeExecutorControlServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeExecutorControlServiceFutureStub>() {
        @java.lang.Override
        public NodeExecutorControlServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeExecutorControlServiceFutureStub(channel, callOptions);
        }
      };
    return NodeExecutorControlServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void enrollNode(com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest request,
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEnrollNodeMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest> controlStream(
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getControlStreamMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest> artifactStream(
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getArtifactStreamMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service NodeExecutorControlService.
   */
  public static abstract class NodeExecutorControlServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return NodeExecutorControlServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service NodeExecutorControlService.
   */
  public static final class NodeExecutorControlServiceStub
      extends io.grpc.stub.AbstractAsyncStub<NodeExecutorControlServiceStub> {
    private NodeExecutorControlServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeExecutorControlServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeExecutorControlServiceStub(channel, callOptions);
    }

    /**
     */
    public void enrollNode(com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest request,
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEnrollNodeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest> controlStream(
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getControlStreamMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest> artifactStream(
        io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getArtifactStreamMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service NodeExecutorControlService.
   */
  public static final class NodeExecutorControlServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<NodeExecutorControlServiceBlockingV2Stub> {
    private NodeExecutorControlServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeExecutorControlServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeExecutorControlServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse enrollNode(com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getEnrollNodeMethod(), getCallOptions(), request);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse>
        controlStream() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getControlStreamMethod(), getCallOptions());
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest, com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse>
        artifactStream() {
      return io.grpc.stub.ClientCalls.blockingClientStreamingCall(
          getChannel(), getArtifactStreamMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service NodeExecutorControlService.
   */
  public static final class NodeExecutorControlServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<NodeExecutorControlServiceBlockingStub> {
    private NodeExecutorControlServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeExecutorControlServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeExecutorControlServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse enrollNode(com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEnrollNodeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service NodeExecutorControlService.
   */
  public static final class NodeExecutorControlServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<NodeExecutorControlServiceFutureStub> {
    private NodeExecutorControlServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeExecutorControlServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeExecutorControlServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse> enrollNode(
        com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEnrollNodeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ENROLL_NODE = 0;
  private static final int METHODID_CONTROL_STREAM = 1;
  private static final int METHODID_ARTIFACT_STREAM = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ENROLL_NODE:
          serviceImpl.enrollNode((com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest) request,
              (io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CONTROL_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.controlStream(
              (io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse>) responseObserver);
        case METHODID_ARTIFACT_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.artifactStream(
              (io.grpc.stub.StreamObserver<com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getEnrollNodeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeRequest,
              com.superskidder.kylinops.backend.nodeexecutor.v2.EnrollNodeResponse>(
                service, METHODID_ENROLL_NODE)))
        .addMethod(
          getControlStreamMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamRequest,
              com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse>(
                service, METHODID_CONTROL_STREAM)))
        .addMethod(
          getArtifactStreamMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamRequest,
              com.superskidder.kylinops.backend.nodeexecutor.v2.ArtifactStreamResponse>(
                service, METHODID_ARTIFACT_STREAM)))
        .build();
  }

  private static abstract class NodeExecutorControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NodeExecutorControlServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.superskidder.kylinops.backend.nodeexecutor.v2.NodeExecutorControlV2Proto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NodeExecutorControlService");
    }
  }

  private static final class NodeExecutorControlServiceFileDescriptorSupplier
      extends NodeExecutorControlServiceBaseDescriptorSupplier {
    NodeExecutorControlServiceFileDescriptorSupplier() {}
  }

  private static final class NodeExecutorControlServiceMethodDescriptorSupplier
      extends NodeExecutorControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    NodeExecutorControlServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (NodeExecutorControlServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NodeExecutorControlServiceFileDescriptorSupplier())
              .addMethod(getEnrollNodeMethod())
              .addMethod(getControlStreamMethod())
              .addMethod(getArtifactStreamMethod())
              .build();
        }
      }
    }
    return result;
  }
}
