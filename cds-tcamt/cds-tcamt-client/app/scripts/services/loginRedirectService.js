/**
 * Decides where to send the user once 'event:loginConfirmed' fires.
 *
 * That event has two sources: an interactive login from a public page, and the
 * session being resolved on a cold page load. Only the first should navigate.
 * Redirecting on the second would throw the user off whatever deep link they
 * opened (e.g. /tp), which is why a refresh used to land on an empty list.
 */
angular.module('tcl').factory('LoginRedirectService', function () {

    // Paths a user can be sitting on that carry no content of their own, and so
    // are safe to navigate away from after a successful login.
    var PUBLIC_PATHS = ['', '/', '/home', '/login'];

    return {
        HOME: '/home',

        /**
         * @param currentPath the path the user is currently on
         * @returns the path to navigate to, or null to stay put
         */
        targetFor: function (currentPath) {
            var path = currentPath || '';
            return PUBLIC_PATHS.indexOf(path) !== -1 ? this.HOME : null;
        }
    };
});
