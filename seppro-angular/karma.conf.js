// Prácticas 13 y 14: Karma para ejecutar las pruebas en local y en GitHub Actions (Chrome sin ventana).
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: { jasmine: { random: false }, clearContext: false },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/seppro-angular'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }, { type: 'lcovonly' }, { type: 'json-summary' }],
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['Chrome'],
    customLaunchers: {
      // En CI (Linux) Chrome corre sin interfaz y sin sandbox
      ChromeHeadlessCI: { base: 'ChromeHeadless', flags: ['--no-sandbox', '--disable-gpu'] },
    },
    restartOnFileChange: true,
  });
};
