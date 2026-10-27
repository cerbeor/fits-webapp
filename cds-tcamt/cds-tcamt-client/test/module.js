// The application module is declared in app/dev/app.js and app/prod/app.js,
// both of which pull in the entire dependency graph. Unit specs only need the
// module to exist so services can register against it.
angular.module('tcl', []);
