# Assembly-only deployment image: tomcat + error page + the war built by the
# workflow. Build context is the repository root (see .dockerignore for what
# actually enters the context).
FROM tomcat:9.0.108-jdk8-temurin-noble
RUN rm -rf /usr/local/tomcat/webapps/*
RUN rm -rf /usr/local/tomcat/webapps.dist
RUN sed -i '/<\/web-app>/i \
    <error-page>\n\
      <exception-type>java.lang.Throwable<\/exception-type>\n\
      <location>/error.html<\/location>\n\
    <\/error-page>\n \
    <error-page>\n\
      <error-code>0<\/error-code>\n\
      <location>/error.html<\/location>\n\
    <\/error-page>\n' /usr/local/tomcat/conf/web.xml
COPY cds-tcamt/error.html /usr/local/tomcat/webapps/ROOT/error.html
COPY cds-tcamt/cds-tcamt-controller/target/fits.war /usr/local/tomcat/webapps/fits.war

# Application logs to stdout. Tomcat sends the webapp's own logger (servlet
# exceptions, Spring startup failures) to a dated file under logs/ that no
# container platform ever reads, which is why an application error used to
# leave no trace outside the box. Add the console handler next to the file
# handler so the same lines reach whatever collects stdout; the file is kept
# for anyone shelled into a running container.
RUN set -eux; \
    F=/usr/local/tomcat/conf/logging.properties; \
    sed -i 's#^\(org\.apache\.catalina\.core\.ContainerBase\.\[Catalina\]\.\[localhost\]\.handlers = \)2localhost\.org\.apache\.juli\.AsyncFileHandler$#\12localhost.org.apache.juli.AsyncFileHandler, java.util.logging.ConsoleHandler#' "$F"; \
    grep -q '^org\.apache\.catalina\.core\.ContainerBase\.\[Catalina\]\.\[localhost\]\.handlers = 2localhost\.org\.apache\.juli\.AsyncFileHandler, java\.util\.logging\.ConsoleHandler$' "$F"

# Deployment branding, applied to the built war rather than to the sources so
# upstream changes to the views keep merging cleanly. Every step asserts its
# end state and fails the build otherwise.
#
# - views/header.html and views/footer.html are replaced wholesale (both ship
#   as single-line files, so anchored insertion cannot land inside them).
# - views/home.html and views/issue.html: the host identity comes out of the
#   welcome text and the triage sentence; the upstream issue tracker link stays.
# - resources/appInfo/appInfo.json: the static fallback carried a personal
#   address; blanked. The API-served appInfo is what the UI normally shows.
# - index.html: tab title and meta author. The analytics strip done at build
#   time must still hold here.
# - The stylesheet index.html links gets the shared chrome rules appended.
#   The minified stylesheet has no trailing newline, so a bare append glues
#   the marker onto the app's one line and the next strip-to-EOF would delete
#   the whole file: split, strip, prove the app rules survived, terminate with
#   a newline, then append.
COPY .github/docker/brand/views/header.html .github/docker/brand/views/footer.html .github/docker/brand/views/about.html /tmp/tpl/views/
COPY .github/docker/brand/sitt-branding.css /tmp/tpl/
RUN set -eux; \
    WAR=/usr/local/tomcat/webapps/fits.war; \
    test -f "$WAR"; \
    \
    # One JavaMail only. mailapi 1.4.3 next to javax.mail 1.6.2 makes the
    # mailer fail with NoSuchMethodError on boots where Tomcat lists it first.
    jar tf "$WAR" | grep -q '^WEB-INF/lib/javax.mail-1.6.2.jar$'; \
    ! jar tf "$WAR" | grep -q '^WEB-INF/lib/mailapi-'; \
    \
    cd /tmp; mkdir -p brand; cd brand; \
    \
    # about: the approved standard disclaimer and notices plus the data use
    # language, replacing the NIST-only page wholesale (the shipped file is one
    # line). The release notes table and version/date bindings are kept.
    cp /tmp/tpl/views/about.html .; \
    grep -q 'ng-controller="AboutCtrl"' about.html; \
    grep -q 'releaseNote in releaseNotes' about.html; \
    grep -q '{{appInfo.version}}' about.html; \
    grep -q '<strong>Tool Information</strong>' about.html; \
    grep -q '<strong>Funding Sources</strong>' about.html; \
    grep -q '<strong>HIPAA Notice</strong>' about.html; \
    grep -q '<strong>Account Ownership and Organizational Content</strong>' about.html; \
    grep -q '<strong>Tool Administration and Operational Access</strong>' about.html; \
    ! grep -q 'NIST assumes no responsibility whatsoever' about.html; \
    mkdir -p views; mv about.html views/; \
    jar uf "$WAR" views/about.html; \
    \
    cp /tmp/tpl/views/header.html /tmp/tpl/views/footer.html .; \
    grep -q 'Standards &amp; Interoperability Testing Tools (SITT)' header.html; \
    grep -q 'navbar.tps' header.html; \
    ! grep -q 'color: #F3C60D">NIST' header.html; \
    grep -q 'Previously known as the NIST tools' footer.html; \
    ! grep -Eq 'nist_logo|itl-logo|nist\.gov' footer.html; \
    mkdir -p views; mv header.html footer.html views/; \
    jar uf "$WAR" views/header.html views/footer.html; \
    \
    jar xf "$WAR" views/home.html; \
    sed -i \
      -e 's|Welcome to the NIST Forecasting for Immunization Test Suite (FITS)|Welcome to the Forecasting for Immunization Test Suite (FITS)|' \
      -e 's|FITS is developed by the NIST in collaboration with the CDC and AIRA\.|FITS was developed in collaboration with the CDC and AIRA.|' \
      views/home.html; \
    ! grep -q 'NIST' views/home.html; \
    jar uf "$WAR" views/home.html; \
    \
    jar xf "$WAR" views/issue.html; \
    sed -i 's|NIST reserves the right to change the priority|The maintainers reserve the right to change the priority|' views/issue.html; \
    ! grep -q 'NIST reserves' views/issue.html; \
    grep -q 'github.com/usnistgov/fits-webapp/issues' views/issue.html; \
    jar uf "$WAR" views/issue.html; \
    \
    jar xf "$WAR" resources/appInfo/appInfo.json; \
    sed -i 's|"adminEmail": *"[^"]*@nist\.gov"|"adminEmail": ""|' resources/appInfo/appInfo.json; \
    ! grep -q '@nist\.gov' resources/appInfo/appInfo.json; \
    jar uf "$WAR" resources/appInfo/appInfo.json; \
    \
    jar xf "$WAR" index.html; \
    sed -i \
      -e 's|<title>[[:space:]]*</title>|<title>Forecasting for Immunization Test Suite (FITS)</title>|' \
      -e 's|<meta name="author" content="NIST">|<meta name="author" content="Prometheus Computing">|' \
      index.html; \
    grep -q '<title>Forecasting for Immunization Test Suite (FITS)</title>' index.html; \
    ! grep -q 'content="NIST"' index.html; \
    ! grep -Eq '_fed_an_ua_tag|dap\.digitalgov\.gov|googletagmanager' index.html; \
    \
    CSS=$(grep -o 'styles/[^"]*main[^"]*\.css' index.html | head -1); \
    test -n "$CSS"; \
    jar tf "$WAR" | grep -qx "$CSS"; \
    jar xf "$WAR" "$CSS"; \
    sed -i 's|/\* sitt-chrome-start .*\*/|\n&|' "$CSS"; \
    sed -i '/sitt-chrome-start/,$d' "$CSS"; \
    test "$(wc -c < "$CSS")" -gt 20000; \
    printf '\n' >> "$CSS"; \
    cat /tmp/tpl/sitt-branding.css >> "$CSS"; \
    test "$(grep -c 'sitt-chrome-start' "$CSS")" -eq 1; \
    test "$(grep -c '#programbrand{' "$CSS")" -eq 1; \
    jar uf "$WAR" index.html "$CSS"; \
    \
    cd /; rm -rf /tmp/brand /tmp/tpl
