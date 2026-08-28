package gov.nist.healthcare.cds.tcamt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import gov.nist.healthcare.cds.auth.domain.Account;
import gov.nist.healthcare.cds.auth.repo.AccountRepository;
import gov.nist.healthcare.cds.auth.service.AccountService;
import org.springframework.beans.factory.access.BootstrapException;

/**
 * Unit tests for {@link Bootstrap#overrideUserId(String, String)}.
 *
 * The properties under test are:
 *   - the old document is removed before the account is re-inserted under the new id, since the
 *     Mongo _id is immutable and a plain save would leave two accounts behind;
 *   - only the account matching the given username is ever mutated;
 *   - a taken id, an unknown username or a failing save never lose the account.
 */
public class BootstrapOverrideUserIdTest {

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

	// --- the account is found and the new id is free ----------------------------------------

	@Test
	public void overridesId_whenUsernameExistsAndNewIdIsFree() {
		Account target = account("old-id", "bob", "bob@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("new-id")).thenReturn(null);

		bootstrap.overrideUserId("bob", "new-id");

		// the previous document must be gone before the new one is written
		InOrder inOrder = Mockito.inOrder(accountRepository);
		inOrder.verify(accountRepository).delete("old-id");
		inOrder.verify(accountRepository).save(target);

		assertEquals("new-id", target.getId());
		assertEquals("username must not be touched", "bob", target.getUsername());
		assertEquals("email must not be touched", "bob@example.org", target.getEmail());
	}

	@Test
	public void savesTheAccountLookedUpByUsername() {
		Account target = account("old-id", "bob", "bob@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("new-id")).thenReturn(null);

		bootstrap.overrideUserId("bob", "new-id");

		ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
		verify(accountRepository).save(saved.capture());
		assertSame("the saved account must be the one looked up by username", target, saved.getValue());
		assertEquals("new-id", saved.getValue().getId());
	}

	@Test
	public void touchesOnlyTheTargetedAccount() {
		Account target = account("old-id", "bob", "bob@example.org");
		Account bystander = account("alice-id", "alice", "alice@example.org");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("new-id")).thenReturn(null);

		bootstrap.overrideUserId("bob", "new-id");

		verify(accService).getAccountByUsername("bob");
		verifyNoMoreInteractions(accService);
		verify(accountRepository).findOne("new-id");
		verify(accountRepository).delete("old-id");
		verify(accountRepository).save(target);
		verifyNoMoreInteractions(accountRepository);

		assertEquals("alice-id", bystander.getId());
	}

	@Test
	public void insertsWithoutDeleting_whenTheAccountHasNoIdYet() {
		Account target = account(null, "bob", "bob@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("new-id")).thenReturn(null);

		bootstrap.overrideUserId("bob", "new-id");

		verify(accountRepository, never()).delete(anyString());
		verify(accountRepository).save(target);
		assertEquals("new-id", target.getId());
	}

	// --- no-op and rejected cases -----------------------------------------------------------

	@Test
	public void isNoOp_whenTheAccountAlreadyHasTheNewId() {
		Account target = account("same-id", "bob", "bob@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);

		bootstrap.overrideUserId("bob", "same-id");

		verify(accountRepository, never()).delete(anyString());
		verify(accountRepository, never()).save(any(Account.class));
		assertEquals("same-id", target.getId());
	}

	@Test
	public void throwsAndDeletesNothing_whenNewIdBelongsToAnotherAccount() {
		Account target = account("old-id", "bob", "bob@example.org");
		Account owner = account("taken-id", "alice", "alice@example.org");

		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("taken-id")).thenReturn(owner);

		try {
			bootstrap.overrideUserId("bob", "taken-id");
			fail("expected a RuntimeException when the id belongs to a different account");
		} catch (RuntimeException expected) {
			assertTrue("message should name the conflicting id, was: " + expected.getMessage(),
					expected.getMessage().contains("taken-id"));
		}

		verify(accountRepository, never()).delete(anyString());
		verify(accountRepository, never()).save(any(Account.class));
		assertEquals("target must keep its id", "old-id", target.getId());
		assertEquals("the other account must keep its id", "taken-id", owner.getId());
	}

	@Test
	public void savesNothing_whenUsernameIsUnknown() {
		when(accService.getAccountByUsername("ghost")).thenReturn(null);

		bootstrap.overrideUserId("ghost", "new-id");

		verify(accountRepository, never()).delete(anyString());
		verify(accountRepository, never()).save(any(Account.class));
	}

	@Test
	public void throwsAndLooksUpNothing_whenNewIdIsEmpty() {
		try {
			bootstrap.overrideUserId("bob", "");
			fail("expected a RuntimeException when the new id is empty");
		} catch (RuntimeException expected) {
			assertTrue("message should name the username, was: " + expected.getMessage(),
					expected.getMessage().contains("bob"));
		}

		verifyNoMoreInteractions(accService);
		verifyNoMoreInteractions(accountRepository);
	}

	// --- the re-insert fails ------------------------------------------------------------------

	@Test
	public void restoresTheAccountUnderItsOldId_whenTheSaveFails() {
		final Account target = account("old-id", "bob", "bob@example.org");
		when(accService.getAccountByUsername("bob")).thenReturn(target);
		when(accountRepository.findOne("new-id")).thenReturn(null);
		// the insert under the new id blows up, the restoring save under the old id succeeds
		when(accountRepository.save(any(Account.class)))
				.thenThrow(new IllegalStateException("mongo is down"))
				.thenReturn(target);

		try {
			bootstrap.overrideUserId("bob", "new-id");
			fail("expected the save failure to be reported");
		} catch (BootstrapException expected) {
			assertTrue("message should name the new id, was: " + expected.getMessage(),
					expected.getMessage().contains("new-id"));
		}

		// the account is written back, and it is written back with the id it had before
		verify(accountRepository, Mockito.times(2)).save(target);
		assertEquals("the account must be left under its original id", "old-id", target.getId());
	}

	// --- helpers ----------------------------------------------------------------------------

	private static Account account(String id, String username, String email) {
		Account account = new Account();
		account.setId(id);
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
