package gov.nist.healthcare.cds.tcamt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.access.BootstrapException;

import gov.nist.healthcare.cds.auth.domain.Account;
import gov.nist.healthcare.cds.auth.repo.AccountRepository;
import gov.nist.healthcare.cds.auth.service.AccountService;
import gov.nist.healthcare.cds.domain.SoftwareConfig;
import gov.nist.healthcare.cds.domain.TestCase;
import gov.nist.healthcare.cds.domain.TestCaseGroup;
import gov.nist.healthcare.cds.domain.TestPlan;
import gov.nist.healthcare.cds.domain.UserMetadata;
import gov.nist.healthcare.cds.domain.ValidationJob;
import gov.nist.healthcare.cds.domain.wrapper.Report;
import gov.nist.healthcare.cds.repositories.ReportRepository;
import gov.nist.healthcare.cds.repositories.SoftwareConfigRepository;
import gov.nist.healthcare.cds.repositories.TestCaseRepository;
import gov.nist.healthcare.cds.repositories.TestPlanRepository;
import gov.nist.healthcare.cds.repositories.UserMetadataRepository;
import gov.nist.healthcare.cds.repositories.ValidationJobRepository;

/**
 * Verifies that {@link Bootstrap#overrideUserId(String, String)} does not break access to the data
 * owned by the renamed user.
 *
 * The premise the override relies on is that every user owned document joins back to its owner by
 * <em>username</em>, never by the account _id :
 * <ul>
 *   <li>{@code TestPlan.user}, {@code TestPlan.viewers} - usernames</li>
 *   <li>{@code TestCase.user} - username</li>
 *   <li>{@code SoftwareConfig.user} - username ({@code SoftwareConfig.userId} is an endpoint
 *       credential, not an account id)</li>
 *   <li>{@code Report.user} - username</li>
 *   <li>{@code ValidationJob.initiator} - username</li>
 *   <li>{@code UserMetadata} - the username <em>is</em> the document _id</li>
 * </ul>
 * These tests populate an in memory stand-in for each of those collections, run the override, and
 * then re-issue the lookups the application actually performs. If a future change ever makes any of
 * this data hang off the account _id, or makes the override touch the username, they fail.
 *
 * The account collection itself is backed by a real map keyed by _id, so a regression where the
 * override saves the account under the new id without removing the old document shows up as two
 * accounts sharing one username.
 */
public class BootstrapOverrideUserIdOwnedDataTest {

	private static final String USERNAME = "bob";
	private static final String OLD_ID = "5f1c0a2e9b4d3c0001aa1111";
	private static final String NEW_ID = "6a2d1b3f0c5e4d0002bb2222";

	private AccountService accService;
	private AccountRepository accountRepository;
	private Bootstrap bootstrap;

	/** Stands in for the account collection : keyed by _id, exactly like Mongo. */
	private Map<String, Account> accounts;

	private TestPlanRepository testPlanRepository;
	private TestCaseRepository testCaseRepository;
	private SoftwareConfigRepository softwareConfigRepository;
	private ReportRepository reportRepository;
	private ValidationJobRepository validationJobRepository;
	private UserMetadataRepository userMetadataRepository;

	private List<TestPlan> testPlans;
	private List<TestCase> testCases;
	private List<SoftwareConfig> softwareConfigs;
	private List<Report> reports;
	private List<ValidationJob> validationJobs;
	private List<UserMetadata> userMetadatas;

	// bob's data
	private Account bob;
	private TestPlan bobPlan;
	private TestCaseGroup bobGroup;
	private TestCase bobTestCase;
	private TestCase bobGroupedTestCase;
	private SoftwareConfig bobConfig;
	private Report bobReport;
	private ValidationJob bobJob;
	private UserMetadata bobMetadata;

	// a second user, who must come out of the override completely untouched
	private Account alice;
	private TestPlan alicePlanSharedWithBob;
	private TestCase aliceTestCase;
	private SoftwareConfig aliceConfig;
	private Report aliceReport;

	@Before
	public void setUp() throws Exception {
		accounts = new LinkedHashMap<>();
		testPlans = new ArrayList<>();
		testCases = new ArrayList<>();
		softwareConfigs = new ArrayList<>();
		reports = new ArrayList<>();
		validationJobs = new ArrayList<>();
		userMetadatas = new ArrayList<>();

		accService = mock(AccountService.class);
		accountRepository = mock(AccountRepository.class);
		testPlanRepository = mock(TestPlanRepository.class);
		testCaseRepository = mock(TestCaseRepository.class);
		softwareConfigRepository = mock(SoftwareConfigRepository.class);
		reportRepository = mock(ReportRepository.class);
		validationJobRepository = mock(ValidationJobRepository.class);
		userMetadataRepository = mock(UserMetadataRepository.class);

		wireAccountCollection();
		wireOwnedDataCollections();
		seedData();

		bootstrap = new Bootstrap();
		inject("accService", accService);
		inject("accountRepository", accountRepository);
	}

	// --- the data stays reachable -------------------------------------------------------------

	@Test
	public void everyOwnedDocumentIsStillReachableByUsername_afterTheIdChange() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		assertEquals("test plans", Arrays.asList(bobPlan), testPlanRepository.findByUser(USERNAME));
		assertSame("test case", bobTestCase, testCaseRepository.findByUser(USERNAME));
		assertEquals("test case count", 2L, testCaseRepository.countByUser(USERNAME));
		assertEquals("software configs", Arrays.asList(bobConfig), softwareConfigRepository.findByUser(USERNAME));
		assertEquals("reports", Arrays.asList(bobReport), reportRepository.findByUser(USERNAME));
		assertEquals("validation jobs", Arrays.asList(bobJob), validationJobRepository.findByInitiator(USERNAME));
		assertSame("user metadata", bobMetadata, userMetadataRepository.findOne(USERNAME));

		// the test cases nested in the plan and in its group are reachable through the plan too
		TestPlan reloaded = testPlanRepository.findByUser(USERNAME).get(0);
		assertEquals(Arrays.asList(bobTestCase), reloaded.getTestCases());
		assertEquals(Arrays.asList(bobGroupedTestCase), reloaded.getTestCaseGroups().get(0).getTestCases());
	}

	@Test
	public void ownerKeysStillResolveToTheRenamedAccount() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		// this is the join the whole override depends on : owner key -> account
		for (String ownerKey : Arrays.asList(bobPlan.getUser(), bobTestCase.getUser(),
				bobGroupedTestCase.getUser(), bobConfig.getUser(), bobReport.getUser(),
				bobJob.getInitiator(), bobMetadata.getUsername())) {
			Account resolved = accountRepository.findByUsername(ownerKey);
			assertNotNull("owner key '" + ownerKey + "' must still resolve to an account", resolved);
			assertSame("owner key '" + ownerKey + "' must resolve to bob", bob, resolved);
			assertEquals("and bob must carry the new id", NEW_ID, resolved.getId());
		}
	}

	@Test
	public void theUsernameIsNeverTouched_soTheOwnerKeysCannotDrift() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		assertEquals(USERNAME, bob.getUsername());
		assertEquals(USERNAME, bobPlan.getUser());
		assertEquals(USERNAME, bobTestCase.getUser());
		assertEquals(USERNAME, bobGroupedTestCase.getUser());
		assertEquals(USERNAME, bobConfig.getUser());
		assertEquals(USERNAME, bobReport.getUser());
		assertEquals(USERNAME, bobJob.getInitiator());
		assertEquals(USERNAME, bobMetadata.getUsername());
	}

	@Test
	public void noOwnedDataDocumentIsRewrittenOrDeleted() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		// nothing joins on the account _id, so the override has no cascade to perform
		verify(testPlanRepository, never()).save(any(TestPlan.class));
		verify(testPlanRepository, never()).delete(any(TestPlan.class));
		verify(testCaseRepository, never()).save(any(TestCase.class));
		verify(testCaseRepository, never()).delete(any(TestCase.class));
		verify(softwareConfigRepository, never()).save(any(SoftwareConfig.class));
		verify(softwareConfigRepository, never()).delete(any(SoftwareConfig.class));
		verify(reportRepository, never()).save(any(Report.class));
		verify(reportRepository, never()).delete(any(Report.class));
		verify(validationJobRepository, never()).save(any(ValidationJob.class));
		verify(validationJobRepository, never()).delete(any(ValidationJob.class));
		verify(userMetadataRepository, never()).save(any(UserMetadata.class));
		verify(userMetadataRepository, never()).delete(anyString());
	}

	@Test
	public void documentIdsOfOwnedDataAreUnchanged() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		assertEquals("tp-1", bobPlan.getId());
		assertEquals("tc-1", bobTestCase.getId());
		assertEquals("tc-2", bobGroupedTestCase.getId());
		assertEquals("sc-1", bobConfig.getId());
		assertEquals("rp-1", bobReport.getId());
		assertEquals("vj-1", bobJob.getId());

		// SoftwareConfig.userId is the credential used against the FHIR endpoint, not an account id
		assertEquals("endpoint-service-account", bobConfig.getUserId());
	}

	// --- sharing ------------------------------------------------------------------------------

	@Test
	public void sharingStillWorksBothWays_afterTheIdChange() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		// bob is a viewer on alice's plan : viewer lists hold usernames
		assertTrue(alicePlanSharedWithBob.getViewers().contains(USERNAME));
		assertEquals(Arrays.asList(alicePlanSharedWithBob), sharedWithUser(USERNAME));
		assertEquals("bob still sees his own plan plus the one shared with him",
				Arrays.asList(bobPlan, alicePlanSharedWithBob), filtred(USERNAME));
	}

	// --- the other user -----------------------------------------------------------------------

	@Test
	public void theOtherUserAndHerDataAreUntouched() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		assertEquals("alice-id", alice.getId());
		assertEquals("alice", alice.getUsername());
		assertSame(alice, accountRepository.findByUsername("alice"));
		assertSame(alice, accountRepository.findOne("alice-id"));

		assertEquals(Arrays.asList(alicePlanSharedWithBob), testPlanRepository.findByUser("alice"));
		assertSame(aliceTestCase, testCaseRepository.findByUser("alice"));
		assertEquals(Arrays.asList(aliceConfig), softwareConfigRepository.findByUser("alice"));
		assertEquals(Arrays.asList(aliceReport), reportRepository.findByUser("alice"));
	}

	// --- the account collection itself ----------------------------------------------------------

	@Test
	public void theAccountCollectionHoldsExactlyOneDocument_underTheNewId() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		assertEquals("bob must exist exactly once : the old document has to be removed, since a "
				+ "save under a new _id inserts rather than renames",
				1, countAccountsWithUsername(USERNAME));
		assertNull("nothing must be left behind under the old id", accountRepository.findOne(OLD_ID));
		assertSame(bob, accountRepository.findOne(NEW_ID));
		assertEquals("only bob and alice", 2, accounts.size());
	}

	@Test
	public void everythingSurvivesAFailedOverride() {
		// the insert under the new id fails ; the account must go back where it was, and every
		// owned document must stay reachable
		final Account[] failOn = {bob};
		when(accountRepository.save(any(Account.class))).thenAnswer(new Answer<Account>() {
			@Override
			public Account answer(InvocationOnMock invocation) {
				Account account = invocation.getArgument(0);
				if (account == failOn[0] && NEW_ID.equals(account.getId())) {
					throw new IllegalStateException("mongo is down");
				}
				accounts.put(account.getId(), account);
				return account;
			}
		});

		try {
			bootstrap.overrideUserId(USERNAME, NEW_ID);
			fail("expected the save failure to be reported");
		} catch (BootstrapException expected) {
			assertTrue(expected.getMessage().contains(NEW_ID));
		}

		assertSame("bob must be back under his original id", bob, accountRepository.findOne(OLD_ID));
		assertNull(accountRepository.findOne(NEW_ID));
		assertEquals(1, countAccountsWithUsername(USERNAME));
		assertEquals(Arrays.asList(bobPlan), testPlanRepository.findByUser(USERNAME));
		assertEquals(Arrays.asList(bobReport), reportRepository.findByUser(USERNAME));
		assertSame(bob, accountRepository.findByUsername(bobPlan.getUser()));
	}

	// --- the one thing the id change does invalidate --------------------------------------------

	/**
	 * {@code UserController} guards its self service endpoints by comparing the account id supplied
	 * by the client against the id of the freshly loaded account (see
	 * {@code GET/POST /accounts/{accountId}} and {@code /accounts/{accountId}/passwordchange}).
	 *
	 * No stored data is lost, but a client still holding the pre-override id - a browser session
	 * opened before the override ran - fails that comparison until it reloads its account. This is
	 * the one user visible consequence of the override, pinned here so it is not a surprise.
	 */
	@Test
	public void clientsHoldingTheOldAccountIdMustRefresh() {
		bootstrap.overrideUserId(USERNAME, NEW_ID);

		Account loaded = accountRepository.findByUsername(USERNAME);
		assertFalse("a stale account id no longer passes the UserController check",
				loaded.getId().equals(OLD_ID));
		assertTrue("the refreshed account id does", loaded.getId().equals(NEW_ID));
	}

	// --- collection stand-ins -------------------------------------------------------------------

	private void wireAccountCollection() {
		when(accountRepository.save(any(Account.class))).thenAnswer(new Answer<Account>() {
			@Override
			public Account answer(InvocationOnMock invocation) {
				Account account = invocation.getArgument(0);
				accounts.put(account.getId(), account);
				return account;
			}
		});
		when(accountRepository.findOne(anyString())).thenAnswer(new Answer<Account>() {
			@Override
			public Account answer(InvocationOnMock invocation) {
				return accounts.get(invocation.<String>getArgument(0));
			}
		});
		when(accountRepository.findByUsername(anyString())).thenAnswer(new Answer<Account>() {
			@Override
			public Account answer(InvocationOnMock invocation) {
				String username = invocation.getArgument(0);
				for (Account account : accounts.values()) {
					if (username.equals(account.getUsername())) {
						return account;
					}
				}
				return null;
			}
		});
		// Bootstrap resolves the account through the service, which delegates to the same collection
		when(accService.getAccountByUsername(anyString())).thenAnswer(new Answer<Account>() {
			@Override
			public Account answer(InvocationOnMock invocation) {
				return accountRepository.findByUsername(invocation.<String>getArgument(0));
			}
		});
		doRemoveOnDelete();
	}

	private void doRemoveOnDelete() {
		org.mockito.Mockito.doAnswer(new Answer<Void>() {
			@Override
			public Void answer(InvocationOnMock invocation) {
				accounts.remove(invocation.<String>getArgument(0));
				return null;
			}
		}).when(accountRepository).delete(anyString());
	}

	private void wireOwnedDataCollections() {
		when(testPlanRepository.findByUser(anyString())).thenAnswer(new Answer<List<TestPlan>>() {
			@Override
			public List<TestPlan> answer(InvocationOnMock invocation) {
				String user = invocation.getArgument(0);
				List<TestPlan> found = new ArrayList<>();
				for (TestPlan tp : testPlans) {
					if (user.equals(tp.getUser())) {
						found.add(tp);
					}
				}
				return found;
			}
		});
		when(testCaseRepository.findByUser(anyString())).thenAnswer(new Answer<TestCase>() {
			@Override
			public TestCase answer(InvocationOnMock invocation) {
				String user = invocation.getArgument(0);
				for (TestCase tc : testCases) {
					if (user.equals(tc.getUser())) {
						return tc;
					}
				}
				return null;
			}
		});
		when(testCaseRepository.countByUser(anyString())).thenAnswer(new Answer<Long>() {
			@Override
			public Long answer(InvocationOnMock invocation) {
				String user = invocation.getArgument(0);
				long count = 0;
				for (TestCase tc : testCases) {
					if (user.equals(tc.getUser())) {
						count++;
					}
				}
				return count;
			}
		});
		when(softwareConfigRepository.findByUser(anyString())).thenAnswer(new Answer<List<SoftwareConfig>>() {
			@Override
			public List<SoftwareConfig> answer(InvocationOnMock invocation) {
				String user = invocation.getArgument(0);
				List<SoftwareConfig> found = new ArrayList<>();
				for (SoftwareConfig sc : softwareConfigs) {
					if (user.equals(sc.getUser())) {
						found.add(sc);
					}
				}
				return found;
			}
		});
		when(reportRepository.findByUser(anyString())).thenAnswer(new Answer<List<Report>>() {
			@Override
			public List<Report> answer(InvocationOnMock invocation) {
				String user = invocation.getArgument(0);
				List<Report> found = new ArrayList<>();
				for (Report r : reports) {
					if (user.equals(r.getUser())) {
						found.add(r);
					}
				}
				return found;
			}
		});
		when(validationJobRepository.findByInitiator(anyString())).thenAnswer(new Answer<List<ValidationJob>>() {
			@Override
			public List<ValidationJob> answer(InvocationOnMock invocation) {
				String initiator = invocation.getArgument(0);
				List<ValidationJob> found = new ArrayList<>();
				for (ValidationJob vj : validationJobs) {
					if (initiator.equals(vj.getInitiator())) {
						found.add(vj);
					}
				}
				return found;
			}
		});
		// UserMetadata is keyed by username : the username IS the document _id
		when(userMetadataRepository.findOne(anyString())).thenAnswer(new Answer<UserMetadata>() {
			@Override
			public UserMetadata answer(InvocationOnMock invocation) {
				String username = invocation.getArgument(0);
				for (UserMetadata um : userMetadatas) {
					if (username.equals(um.getUsername())) {
						return um;
					}
				}
				return null;
			}
		});
	}

	/** Mirrors {@code TestPlanRepository.sharedWithUser} : not mine, and either shared or public. */
	private List<TestPlan> sharedWithUser(String user) {
		List<TestPlan> found = new ArrayList<>();
		for (TestPlan tp : testPlans) {
			boolean mine = user.equals(tp.getUser());
			boolean visible = tp.isPublic() || (tp.getViewers() != null && tp.getViewers().contains(user));
			if (!mine && visible) {
				found.add(tp);
			}
		}
		return found;
	}

	/** Mirrors {@code TestPlanRepository.filtred} : mine, or shared with me. */
	private List<TestPlan> filtred(String user) {
		List<TestPlan> found = new ArrayList<>();
		for (TestPlan tp : testPlans) {
			if (user.equals(tp.getUser()) || (tp.getViewers() != null && tp.getViewers().contains(user))) {
				found.add(tp);
			}
		}
		return found;
	}

	private int countAccountsWithUsername(String username) {
		int count = 0;
		for (Account account : accounts.values()) {
			if (username.equals(account.getUsername())) {
				count++;
			}
		}
		return count;
	}

	// --- fixture ---------------------------------------------------------------------------------

	private void seedData() {
		bob = account(OLD_ID, USERNAME, "bob@example.org");
		alice = account("alice-id", "alice", "alice@example.org");
		accounts.put(bob.getId(), bob);
		accounts.put(alice.getId(), alice);

		bobTestCase = testCase("tc-1", "Bob TC", USERNAME);
		bobGroupedTestCase = testCase("tc-2", "Bob grouped TC", USERNAME);
		aliceTestCase = testCase("tc-3", "Alice TC", "alice");
		testCases.addAll(Arrays.asList(bobTestCase, bobGroupedTestCase, aliceTestCase));

		bobGroup = new TestCaseGroup();
		bobGroup.setId("tcg-1");
		bobGroup.setName("Bob group");
		bobGroup.setTestPlan("tp-1");
		bobGroup.setTestCases(new ArrayList<>(Arrays.asList(bobGroupedTestCase)));

		bobPlan = testPlan("tp-1", "Bob TP", USERNAME);
		bobPlan.setTestCases(new ArrayList<>(Arrays.asList(bobTestCase)));
		bobPlan.setTestCaseGroups(new ArrayList<>(Arrays.asList(bobGroup)));

		alicePlanSharedWithBob = testPlan("tp-2", "Alice TP shared with Bob", "alice");
		alicePlanSharedWithBob.setViewers(new ArrayList<>(Arrays.asList(USERNAME)));
		alicePlanSharedWithBob.setTestCases(new ArrayList<>(Arrays.asList(aliceTestCase)));
		testPlans.addAll(Arrays.asList(bobPlan, alicePlanSharedWithBob));

		bobConfig = softwareConfig("sc-1", "Bob engine", USERNAME);
		aliceConfig = softwareConfig("sc-2", "Alice engine", "alice");
		softwareConfigs.addAll(Arrays.asList(bobConfig, aliceConfig));

		bobReport = report("rp-1", "tc-1", USERNAME);
		aliceReport = report("rp-2", "tc-3", "alice");
		reports.addAll(Arrays.asList(bobReport, aliceReport));

		bobJob = new ValidationJob();
		bobJob.setId("vj-1");
		bobJob.setInitiator(USERNAME);
		bobJob.setTarget(bobConfig);
		validationJobs.add(bobJob);

		bobMetadata = new UserMetadata();
		bobMetadata.setUsername(USERNAME);
		bobMetadata.setTestCases(2);
		bobMetadata.setExecutions(1);
		userMetadatas.add(bobMetadata);
	}

	private static Account account(String id, String username, String email) {
		Account account = new Account();
		account.setId(id);
		account.setUsername(username);
		account.setEmail(email);
		return account;
	}

	private static TestPlan testPlan(String id, String name, String user) {
		TestPlan tp = new TestPlan();
		tp.setId(id);
		tp.setName(name);
		tp.setUser(user);
		tp.setTestCaseGroups(new ArrayList<TestCaseGroup>());
		return tp;
	}

	private static TestCase testCase(String id, String name, String user) {
		TestCase tc = new TestCase();
		tc.setId(id);
		tc.setName(name);
		tc.setUser(user);
		return tc;
	}

	private static SoftwareConfig softwareConfig(String id, String name, String user) {
		SoftwareConfig sc = new SoftwareConfig();
		sc.setId(id);
		sc.setName(name);
		sc.setUser(user);
		// deliberately set : this is the credential for the FHIR endpoint, NOT an account id
		sc.setUserId("endpoint-service-account");
		return sc;
	}

	private static Report report(String id, String tc, String user) {
		Report r = new Report();
		r.setId(id);
		r.setTc(tc);
		r.setUser(user);
		return r;
	}

	private void inject(String fieldName, Object value) throws Exception {
		Field field = Bootstrap.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(bootstrap, value);
	}
}
