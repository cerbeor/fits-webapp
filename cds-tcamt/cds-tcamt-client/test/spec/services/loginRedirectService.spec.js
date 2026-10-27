'use strict';

describe('LoginRedirectService', function () {
    var LoginRedirectService;

    beforeEach(module('tcl'));
    beforeEach(inject(function (_LoginRedirectService_) {
        LoginRedirectService = _LoginRedirectService_;
    }));

    // The bug: 'event:loginConfirmed' also fires when the session is resolved on
    // a cold page load. Redirecting then throws the user off their deep link.
    describe('on a deep link, after the session resolves', function () {
        it('stays on /tp', function () {
            expect(LoginRedirectService.targetFor('/tp')).toBeNull();
        });

        it('stays on any other content route', function () {
            ['/doc', '/setting', '/about', '/contact'].forEach(function (path) {
                expect(LoginRedirectService.targetFor(path)).toBeNull();
            });
        });
    });

    describe('after an interactive login from a public page', function () {
        it('redirects to home', function () {
            ['', '/', '/home', '/login'].forEach(function (path) {
                expect(LoginRedirectService.targetFor(path)).toBe('/home');
            });
        });
    });

    describe('edge cases', function () {
        it('treats an absent path as public and redirects home', function () {
            expect(LoginRedirectService.targetFor(undefined)).toBe('/home');
            expect(LoginRedirectService.targetFor(null)).toBe('/home');
        });

        // Guards the old behaviour: it redirected to '/ig', which is not a
        // registered route and so fell through 'otherwise' to '/'.
        it('never targets the non-existent /ig route', function () {
            ['/tp', '/home', '', '/doc'].forEach(function (path) {
                expect(LoginRedirectService.targetFor(path)).not.toBe('/ig');
            });
        });
    });
});
