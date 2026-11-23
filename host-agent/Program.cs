using AniGamesReady.HostAgent;

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "Ani Games Ready Remote Host");
builder.Services.AddHostedService<HostAgentWorker>();
await builder.Build().RunAsync();
