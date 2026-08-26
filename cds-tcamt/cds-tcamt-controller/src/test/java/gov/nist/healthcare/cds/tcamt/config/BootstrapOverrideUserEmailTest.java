package gov.nist.healthcare.cds.tcamt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import gov.nist.healthcare.cds.auth.domain.Account;
import gov.nist.healthcare.cds.auth.repo.AccountRepository;
import gov.nist.healthcare.cds.auth.service.AccountService;
import org.springframework.beans.factory.access.BootstrapException;

/**
 * Unit tests for {@link Bootstrap#overrideUserEmail(String, String)}.
 *
 * The two properties under test are:
 *   - only the account matching the given username is ever mutated and saved;
 *   - the "new email already in use" cases are handled without corrupting anything.
 */
public class BootstrapOverrideUserEmailTest {

	private AccountService accService;
	private AccountRepository accountRepository;
	private Bootstrap bootstrap;

	@Before
	public void setUp() throws Exception {
		accService = mock(AccountService.class);
		accountRepository = mock(AccountRepository.class);

		bootstrap = new Bootstrap();
		inject("accService", accService);
		inject("accountRepository", accountRepository);
	}

	// --- the account is found and the new email is free -------------------------------------

	@Test
	public void overridesEmail_whenUsernameExistsAndNewEmailIsFree() {
		Account target = account("bob", "bob.old@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findByEmailIgnoreCase("bob.new@example.org")).thenReturn(null);

		bootstrap.overrideUserEmail("bob", "bob.new@example.org");

		ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
		verify(accountRepository).save(saved.capture());
		assertSame("the saved account must be the one looked up by username", target, saved.getValue());
		assertEquals("bob.new@example.org", saved.getValue().getEmail());
		assertEquals("username must not be touched", "bob", saved.getValue().getUsername());
	}

	@Test
	public void looksUpAndSavesOnlyTheTargetedAccount() {
		Account target = account("bob", "bob.old@example.org");
		Account bystander = account("alice", "alice@example.org");
		Account otherBystander = account("carol", "carol@example.org");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findByEmailIgnoreCase("bob.new@example.org")).thenReturn(null);

		bootstrap.overrideUserEmail("bob", "bob.new@example.org");

		// only "bob" is ever resolved, and nothing but the email lookup + the single save happens
		verify(accService).getAccountByUsername("bob");
		verifyNoMoreInteractions(accService);
		verify(accountRepository).findByEmailIgnoreCase("bob.new@example.org");
		verify(accountRepository).save(target);
		verifyNoMoreInteractions(accountRepository);

		assertEquals("alice@example.org", bystander.getEmail());
		assertEquals("carol@example.org", otherBystander.getEmail());
	}

	// --- the new email is already in use ----------------------------------------------------

	@Test
	public void throwsAndSavesNothing_whenNewEmailBelongsToAnotherAccount() {
		Account target = account("bob", "bob.old@example.org");
		Account owner = account("alice", "taken@example.org");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findByEmailIgnoreCase("taken@example.org")).thenReturn(owner);

		try {
			bootstrap.overrideUserEmail("bob", "taken@example.org");
			fail("expected a RuntimeException when the email belongs to a different account");
		} catch (RuntimeException expected) {
			assertTrue(
					"message should name the conflicting email, was: " + expected.getMessage(),
					expected.getMessage().contains("taken@example.org"));
		}

		verify(accountRepository, never()).save(any(Account.class));
		assertEquals("target must keep its email", "bob.old@example.org", target.getEmail());
		assertEquals("the other account must keep its email", "taken@example.org", owner.getEmail());
	}

	@Test
	public void isNoOp_whenNewEmailAlreadyBelongsToTheTargetedAccount() {
		Account target = account("bob", "bob@example.org");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findByEmailIgnoreCase("bob@example.org")).thenReturn(target);

		bootstrap.overrideUserEmail("bob", "bob@example.org");

		verify(accountRepository, never()).save(any(Account.class));
		assertEquals("bob@example.org", target.getEmail());
	}

	@Test
	public void isNoOp_whenNewEmailMatchesTheTargetedAccountIgnoringCase() {
		Account target = account("bob", "Bob@Example.ORG");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findByEmailIgnoreCase("bob@example.org")).thenReturn(target);

		bootstrap.overrideUserEmail("bob", "bob@example.org");

		verify(accountRepository, never()).save(any(Account.class));
		// the comparison is case insensitive, so the stored casing is deliberately left alone
		assertEquals("Bob@Example.ORG", target.getEmail());
	}

	// --- unknown username -------------------------------------------------------------------

	@Test
	public void savesNothing_whenUsernameIsUnknownAndEmailIsFree() {
		when(accService.getAccountByUsername("ghost")).thenReturn(null);
		when(accountRepository.findByEmailIgnoreCase("ghost@example.org")).thenReturn(null);

		bootstrap.overrideUserEmail("ghost", "ghost@example.org");

		verify(accountRepository, never()).save(any(Account.class));
	}

	/**
	 * Documents current behaviour, which is a latent bug: when the username is unknown the null
	 * check on line 194 is reached only if the email is free. If the email is already taken,
	 * {@code accountByUsername.getEmail()} dereferences null first.
	 *
	 * Hoisting the {@code accountByUsername == null} check above the {@code accountByEmail != null}
	 * block turns this into the same quiet no-op as
	 * {@link #savesNothing_whenUsernameIsUnknownAndEmailIsFree()}; flip this test if that is done.
	 */
	@Test
	public void currentBehaviour_throwsBootstrap_whenUsernameIsUnknownAndEmailIsTaken() {
		when(accService.getAccountByUsername("ghost")).thenReturn(null);
		when(accountRepository.findByEmailIgnoreCase("taken@example.org"))
				.thenReturn(account("alice", "taken@example.org"));

		try {
			bootstrap.overrideUserEmail("ghost", "taken@example.org");
			fail("expected the current implementation to dereference a null account");
		} catch (BootstrapException expected) {
			// see javadoc above
		}

		verify(accountRepository, never()).save(any(Account.class));
	}

	// --- helpers ----------------------------------------------------------------------------

	private static Account account(String username, String email) {
		Account account = new Account();
		account.setUsername(username);
		account.setEmail(email);
		return account;
	}

	private void inject(String fieldName, Object value) throws Exception {
		Field field = Bootstrap.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(bootstrap, value);
	}
}
