// Karma configuration for unit tests.
// Run with: grunt test  (or: npx karma start test/karma.conf.js --single-run)
module.exports = function (config) {
    config.set({
        basePath: '../',
        frameworks: ['jasmine'],
        files: [
            'bower_components/angular/angular.js',
            'bower_components/angular-mocks/angular-mocks.js',
            // Minimal module definition so specs can exercise individual
            // services without bootstrapping the whole application.
            'test/module.js',
            'app/scripts/services/loginRedirectService.js',
            'app/scripts/controllers/testplans.js',
            'test/spec/**/*.js'
        ],
        exclude: [],
        port: 10087,
        logLevel: config.LOG_INFO,
        autoWatch: false,
        // karma-chrome-launcher 0.2.x predates the built-in ChromeHeadless
        // browser, so define it here.
        browsers: ['ChromeHeadlessNoSandbox'],
        customLaunchers: {
            ChromeHeadlessNoSandbox: {
                base: 'Chrome',
                flags: ['--headless', '--disable-gpu', '--no-sandbox', '--remote-debugging-port=9222']
            }
        },
        singleRun: true
    });
};
