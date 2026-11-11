package otelsetup

import (
	"context"
	"net/url"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

type Config struct {
	Enabled     bool
	Endpoint    string
	Insecure    bool
	ServiceName string
}

func Init(ctx context.Context, cfg Config) (func(context.Context) error, error) {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	if !cfg.Enabled {
		return func(context.Context) error { return nil }, nil
	}

	endpoint, insecure := normalizeEndpoint(cfg.Endpoint, cfg.Insecure)
	opts := []otlptracegrpc.Option{otlptracegrpc.WithEndpoint(endpoint)}
	if insecure {
		opts = append(opts, otlptracegrpc.WithInsecure())
	}
	exporter, err := otlptracegrpc.New(ctx, opts...)
	if err != nil {
		return nil, err
	}
	serviceName := strings.TrimSpace(cfg.ServiceName)
	if serviceName == "" {
		serviceName = "kylin-node-executor"
	}
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewWithAttributes(
			"",
			attribute.String("service.name", serviceName),
			attribute.String("service.namespace", "kylinops"),
			attribute.String("deployment.environment", "demo"),
		)),
	)
	otel.SetTracerProvider(provider)
	return provider.Shutdown, nil
}

func Tracer() trace.Tracer {
	return otel.Tracer("kylin-node-executor")
}

func ContextWithTraceParent(ctx context.Context, traceparent string) context.Context {
	traceparent = strings.TrimSpace(traceparent)
	if traceparent == "" {
		return ctx
	}
	return propagation.TraceContext{}.Extract(ctx, propagation.MapCarrier{"traceparent": traceparent})
}

func TraceParent(ctx context.Context) string {
	carrier := propagation.MapCarrier{}
	propagation.TraceContext{}.Inject(ctx, carrier)
	return strings.TrimSpace(carrier["traceparent"])
}

func normalizeEndpoint(endpoint string, insecure bool) (string, bool) {
	endpoint = strings.TrimSpace(endpoint)
	if endpoint == "" {
		return "127.0.0.1:4317", insecure
	}
	parsed, err := url.Parse(endpoint)
	if err == nil && parsed.Host != "" {
		if parsed.Scheme == "http" {
			insecure = true
		}
		return parsed.Host, insecure
	}
	return strings.TrimPrefix(strings.TrimPrefix(endpoint, "http://"), "https://"), insecure
}
