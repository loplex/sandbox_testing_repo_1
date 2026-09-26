// Chrome without a GPU it may use renders WebGL 2 with SwiftShader, in software, only when told it may.
config.set({
    customLaunchers: {
        ChromeHeadlessWebGl2: {
            base: "ChromeHeadless",
            flags: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
        },
    },
    browsers: ["ChromeHeadlessWebGl2"],
});
