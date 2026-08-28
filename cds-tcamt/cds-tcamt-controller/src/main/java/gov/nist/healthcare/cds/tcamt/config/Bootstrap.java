package gov.nist.healthcare.cds.tcamt.config;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.xml.bind.JAXBException;

import com.google.common.base.Strings;
import gov.nist.fhir.client.ir.TestRunnerServiceFhirImpl;
import gov.nist.healthcare.cds.auth.domain.Account;
import gov.nist.healthcare.cds.auth.domain.Privilege;
import gov.nist.healthcare.cds.auth.repo.AccountRepository;
import gov.nist.healthcare.cds.auth.repo.PrivilegeRepository;
import gov.nist.healthcare.cds.auth.service.AccountService;
import gov.nist.healthcare.cds.domain.wrapper.AppInfo;
import gov.nist.healthcare.cds.domain.wrapper.Document;
import gov.nist.healthcare.cds.domain.wrapper.Documents;
import gov.nist.healthcare.cds.domain.wrapper.Resources;
import gov.nist.healthcare.cds.service.TestRunnerService;
import gov.nist.healthcare.cds.service.VaccineMatcherService;
import gov.nist.healthcare.cds.service.impl.data.SimpleCodeRemapService;
import gov.nist.healthcare.cds.service.impl.persist.SimpleDatabaseCleanupService;
import gov.nist.healthcare.cds.service.impl.persist.MongoExaminationService;
import gov.nist.healthcare.cds.service.impl.validation.ConfigurableVaccineMatcher;
import gov.nist.healthcare.cds.service.vaccine.VaccineMatcherConfiguration;

import org.springframework.beans.factory.access.BootstrapException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.oxm.Marshaller;
import org.springframework.oxm.castor.CastorMarshaller;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@PropertySource("classpath:application.properties")
public class Bootstrap {

	@Value("${FITS_UPGRADE_REMAP_DISABLE:false}")
	private boolean FITS_UPGRADE_REMAP_DISABLE;

	@Autowired
	private Environment env;
	
	@Autowired
	private PrivilegeRepository privilegeRepository;
	
	@Autowired
	private AccountService accService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private SimpleCodeRemapService simpleCodeRemapService;

	@Autowired
	private SimpleDatabaseCleanupService databaseCleanupService;

	private String ENV_CLEANUP_DATABASE = "fits.admin.cleanup-database";
	private String ENV_CLEANUP_WHITELIST_USERNAMES = "fits.admin.cleanup-whitelist.usernames";
	private String ENV_CLEANUP_WHITELIST_EMAILS = "fits.admin.cleanup-whitelist.emails";
	private String ENV_ADMIN_CREATE = "fits.admin.create-if-not-exists";
	private String ENV_ADMIN_PASSWORD = "fits.admin.password";
	private String ENV_ADMIN_EMAIL = "fits.admin.email";
	private String ENV_WEB_ADMIN_EMAIL = "fits.admin.web.email";
	private String ENV_EMAIL_HOST = "fits.email.host";
	private String ENV_EMAIL_PORT = "fits.email.port";
	private String ENV_EMAIL_PROTOCOL = "fits.email.protocol";
	private String ENV_EMAIL_SMTP_AUTH = "fits.email.smtp.auth";
	private String ENV_EMAIL_FROM = "fits.email.from";
	private String ENV_EMAIL_SUBJECT = "fits.email.subject";
	private String ENV_ADAPTER_URL = "fits.adapter.url";
	@Autowired
	private MongoExaminationService mongoExaminationService;

	@Bean
	public String adminEmail() {
		return env.getProperty(ENV_ADMIN_EMAIL);
	}

	@Bean 
	public AppInfo appInfo() throws ParseException {
		AppInfo app = new AppInfo();
		SimpleDateFormat formatter = new SimpleDateFormat("MM-dd-yyyy");
		app.setAdminEmail(env.getProperty(ENV_WEB_ADMIN_EMAIL));
		app.setDate(formatter.parse(env.getProperty("app.date")));
		app.setVersion(env.getProperty("app.version"));
		return app;
	}
	
	@Bean
	public JavaMailSenderImpl mailSender() {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(env.getProperty(ENV_EMAIL_HOST));
		mailSender.setPort(Integer.parseInt(env.getProperty(ENV_EMAIL_PORT)));
		mailSender.setProtocol(env.getProperty(ENV_EMAIL_PROTOCOL));
		Properties javaMailProperties = new Properties();
		javaMailProperties.setProperty("mail.smtp.auth",env.getProperty(ENV_EMAIL_SMTP_AUTH));
		javaMailProperties.setProperty("mail.debug","true");
		mailSender.setJavaMailProperties(javaMailProperties);
		return mailSender;
	}

	@Bean
	public org.springframework.mail.SimpleMailMessage templateMessage() {
		org.springframework.mail.SimpleMailMessage templateMessage = new org.springframework.mail.SimpleMailMessage();
		templateMessage.setFrom(env.getProperty(ENV_EMAIL_FROM));
		templateMessage.setSubject(env.getProperty(ENV_EMAIL_SUBJECT));
		return templateMessage;
	}
	
	@Bean
	public VaccineMatcherService matcher() {
		return new ConfigurableVaccineMatcher();
	}
	
	@Bean
	public VaccineMatcherConfiguration matcherConfig() throws JAXBException {
		return new VaccineMatcherConfiguration(Bootstrap.class.getResourceAsStream("/cdc/groups-mapping.xml"));
	}
	
	@Bean
	public Marshaller castorM() {
		return new CastorMarshaller();
	}
	
	@Bean
	public TestRunnerService testRunner() {
		return new TestRunnerServiceFhirImpl(env.getProperty(ENV_ADAPTER_URL));
	}

	@Bean
	public Documents documents() throws IOException {
		Documents docs = new Documents();
		ObjectMapper mapper = new ObjectMapper();
		List<Document> myObjects = mapper.readValue(Bootstrap.class.getResourceAsStream("/docs/documents.json"), mapper.getTypeFactory().constructCollectionType(List.class, Document.class));
		docs.setDocs(myObjects);
		return docs;
	}
	
	@Bean
	public Resources resources() throws IOException {
		Resources docs = new Resources();
		ObjectMapper mapper = new ObjectMapper();
		List<Document> myObjects = mapper.readValue(Bootstrap.class.getResourceAsStream("/doc_resources/documents.json"), mapper.getTypeFactory().constructCollectionType(List.class, Document.class));
		docs.setResources(myObjects);
		return docs;
	}

	public void createAdminUser() {
		Account admin = this.accService.getAccountByUsername("admin");
		String password = env.getProperty(ENV_ADMIN_PASSWORD);
		String email = env.getProperty(ENV_ADMIN_EMAIL);
		if(admin == null && !Strings.isNullOrEmpty(password) && !Strings.isNullOrEmpty(email)) {
			List<String> issues = this.accService.checkPasswordPolicy(password);
			if(issues.size() > 0) {
				System.out.println("[ADMIN USER CREATE] Invalid admin password: " + String.join(", ", issues));
			} else {
				Account account = new Account();
				account.setUsername("admin");
				account.setPassword(password);
				account.setEmail(email);
				account.setPending(false);
				this.accService.createAdmin(account);
				System.out.println("[ADMIN USER CREATED] Username: admin, Email: "+ email);
			}
		} else {
			System.out.println("[ADMIN USER NOT CREATED] admin user already exists, or password and email not provided");
		}
	}


	public void overrideUserEmail(String username, String newEmail) {
		Account accountByUsername = this.accService.getAccountByUsername(username);
		Account accountByEmail = this.accountRepository.findByEmailIgnoreCase(newEmail);

		if(accountByEmail != null) {
			if (accountByEmail.getUsername().equalsIgnoreCase(username)) {
				System.out.println("[ADMIN USER UPDATED] Email already in use " + newEmail);
				return;
			}
			throw new BootstrapException("[Account for EMAIL Override ALREADY EXISTS] " + newEmail);
		}
		if(accountByUsername == null ) {
			System.out.println("[User Email Override] Invalid account username: " + accountByUsername);
		} else {
			String oldEmail = accountByUsername.getEmail();
			accountByUsername.setEmail(newEmail);
			this.accountRepository.save(accountByUsername);
			System.out.println("[User Email Override] Account username: " + accountByUsername.getUsername() + ", email " + oldEmail + " overriden to new email: " + newEmail);
		}
	}

	/**
	 * Overrides the id (the Mongo _id) of the account matching the given username.
	 *
	 * The _id of a document is immutable, so the rename is done by removing the existing document
	 * and re-inserting the very same account under the new id. Nothing else in the database points
	 * at an account _id : test plans, test cases, reports, software configs, validation jobs, user
	 * metadata and password resets all key off the username, so no other collection is migrated here.
	 */
	public void overrideUserId(String username, String newId) {
		if(Strings.isNullOrEmpty(newId)) {
			throw new BootstrapException("[User Id Override] Empty new id provided for username: " + username);
		}

		Account account = this.accService.getAccountByUsername(username);
		if(account == null) {
			System.out.println("[User Id Override] Invalid account username: " + username);
			return;
		}

		String oldId = account.getId();
		if(newId.equals(oldId)) {
			System.out.println("[User Id Override] Account username: " + username + " already has id: " + newId);
			return;
		}

		Account accountById = this.accountRepository.findOne(newId);
		if(accountById != null) {
			throw new BootstrapException("[Account for ID Override ALREADY EXISTS] " + newId + " is used by username: " + accountById.getUsername());
		}

		if(oldId != null) {
			this.accountRepository.delete(oldId);
		}
		account.setId(newId);
		try {
			this.accountRepository.save(account);
		} catch (RuntimeException e) {
			// never leave the account deleted : put it back under the id it had before the override
			account.setId(oldId);
			this.accountRepository.save(account);
			throw new BootstrapException("[User Id Override] Could not save account username: " + username + " under new id: " + newId, e);
		}
		System.out.println("[User Id Override] Account username: " + username + ", id " + oldId + " overriden to new id: " + newId);
	}

	/**
	 * Reads a comma separated property (usually fed by an environment variable) as a set of
	 * trimmed, non empty values. Returns an empty set when the property is absent or blank.
	 */
	private Set<String> commaSeparatedProperty(String property) {
		Set<String> values = new HashSet<>();
		String raw = env.getProperty(property);
		if(Strings.isNullOrEmpty(raw)) {
			return values;
		}
		for(String value : raw.split(",")) {
			String trimmed = value.trim();
			if(!trimmed.isEmpty()) {
				values.add(trimmed);
			}
		}
		return values;
	}

	public void createPrivileges(){
		Privilege p;
		String pr = "";
		if(privilegeRepository.findByRole("ADMIN") == null){
			p = new Privilege();
			p.setId("1");
			p.setRole("ADMIN");
			privilegeRepository.save(p);
			pr = " ADMIN ";
		}
		if(privilegeRepository.findByRole("TESTER") == null){
			p = new Privilege();
			p.setId("2");
			p.setRole("TESTER");
			privilegeRepository.save(p);
			pr += " TESTER ";
		}
		System.out.println("[PRIVILEGE CREATED]"+(pr.isEmpty() ? " NONE " : pr ));
	}
	

	@PostConstruct
	public void init() throws Exception {

		// Create Privileges
		this.createPrivileges();

		if("true".equals(env.getProperty(ENV_ADMIN_CREATE))) {
			// Create admin user
			this.createAdminUser();
		}

		//Create Vaccines
		Map<String, String> cvxMapping = new HashMap<>();

		Map<String, String> productMapping = new HashMap<>();

		// Original : "AstraZeneca COVID-19 Vaccine (Non-US tradenames include VAXZEVRIA, COVISHIELD)"
		productMapping.put("210:ASZ:AstraZeneca COVID-19 Vaccine (includes non-US tradenames VAXZEVRIA, COVISHIELD)", "210:ASZ:AstraZeneca COVID-19 Vaccine (Non-US tradenames include VAXZEVRIA, COVISHIELD)");

		// Original : "Moderna COVID-19 Vaccine (non-US Spikevax)"
		productMapping.put("207:MOD:Moderna COVID-19 Vaccine (includes non-US tradename Spikevax)", "207:MOD:Moderna COVID-19 Vaccine (non-US Spikevax)");

		productMapping.put("208:PFR:Pfizer-BioNTech COVID-19 Vaccine", "208:PFR:Pfizer-BioNTech COVID-19 Vaccine (EUA labeled)  COMIRNATY (BLA labeled)");

		productMapping.put("211:NVX:Novavax COVID-19 Vaccine", "211:NVX:Novavax COVID-19 Vaccine (Non-US Tradenames NUVAXOVID, COVOVAX)");

		productMapping.put("229:MOD:Moderna COVID-19 Vaccine (non-US Spikevax)", "229:MOD:Moderna COVID-19 Bivalent, Original + BA.4/BA.5 (Non-US Tradename Spikevax Bivalent)");

		productMapping.put("230:MOD:Moderna COVID-19 Vaccine (non-US Spikevax)", "230:MOD:Moderna COVID-19 Bivalent, Original + BA.4/BA.5 (Non-US Tradename Spikevax Bivalent)");

		productMapping.put("300:PFR:Pfizer-BioNTech COVID-19 Vaccine (EUA labeled)  COMIRNATY (BLA labeled)", "300:PFR:Pfizer-BioNTech COVID-19 Bivalent, Original + BA.4/BA.5 (Non-US Tradename COMIRNATY Bivalent)");

		productMapping.put("301:PFR:Pfizer-BioNTech COVID-19 Vaccine (EUA labeled)  COMIRNATY (BLA labeled)", "301:PFR:Pfizer-BioNTech COVID-19 Bivalent, Original + BA.4/BA.5 (Non-US Tradename COMIRNATY Bivalent)");

		productMapping.put("302:PFR:Pfizer-BioNTech COVID-19 Vaccine (EUA labeled)  COMIRNATY (BLA labeled)", "302:PFR:Pfizer-BioNTech COVID-19 Bivalent, Original + BA.4/BA.5 (Non-US Tradename COMIRNATY Bivalent)");

		productMapping.put("158:SEQ:Afluria, quadrivalent", "158:SEQ:Afluria quadrivalent, with preservative");

		// Whitelists are configured through FITS_CLEANUP_WHITELIST_USERNAMES / FITS_CLEANUP_WHITELIST_EMAILS
		// as comma separated lists. "admin" and "protected" are always kept, whatever the configuration is.
		Set<String> whiteListedUsernames = this.commaSeparatedProperty(ENV_CLEANUP_WHITELIST_USERNAMES);
		Collections.addAll(whiteListedUsernames, "admin", "protected");
		Set<String> whiteListedEmails = this.commaSeparatedProperty(ENV_CLEANUP_WHITELIST_EMAILS);
		Logger.getLogger(Bootstrap.class.getName()).log(Level.INFO, "[DATABASE EVAL] Whitelisted {0} username(s) and {1} email(s)", new Object[]{whiteListedUsernames.size(), whiteListedEmails.size()});
//		List<UserContact> contacts =  this.mongoExaminationService.findUsersToContact(5, whiteListedUsernames,whiteListedEmails);
//		Logger.getLogger(Bootstrap.class.getName()).log(Level.INFO, "[DATABASE EVAL] Found {0} contacts", contacts.size());
//		for(UserContact contact : contacts){
//			Logger.getLogger(Bootstrap.class.getName()).log(Level.INFO, "[DATABASE EVAL] Found contact {0}", contact);
//		}
//		for(UserContact contact : contacts){
//			System.out.println(contact.getEmail());
//		}
		if("true".equals(env.getProperty(ENV_CLEANUP_DATABASE))) {
			this.databaseCleanupService.cleanDatabase(whiteListedUsernames, whiteListedEmails);
		}

		if (FITS_UPGRADE_REMAP_DISABLE)  {
			Logger.getLogger(Bootstrap.class.getName()).log(Level.WARNING, "Bootstrap upgrade and remap on testcases disabled, not recommended when changing around versions. Enable with FITS_2_UPGRADE_REMAP_DISABLE=true");
		} else {
			this.simpleCodeRemapService.reloadCodeSetsAndRemapTestCases(
					Bootstrap.class.getResourceAsStream("/codeset/web_cvx.xlsx"),
					Bootstrap.class.getResourceAsStream("/codeset/web_vax2vg.xlsx"),
					Bootstrap.class.getResourceAsStream("/codeset/web_mvx.xlsx"),
					Bootstrap.class.getResourceAsStream("/codeset/web_tradename.xlsx"),
					cvxMapping,
					productMapping
			);
		}
	}


	
}