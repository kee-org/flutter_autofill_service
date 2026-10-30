import 'package:flutter/material.dart';

/// A plain username/password form for trying out autofill against the example
/// service.
class TestLoginForm extends StatelessWidget {
  const TestLoginForm();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Test login form')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: AutofillGroup(
          child: Column(
            children: const [
              TextField(
                decoration: InputDecoration(labelText: 'Username'),
                autofillHints: [AutofillHints.username],
              ),
              TextField(
                decoration: InputDecoration(labelText: 'Password'),
                obscureText: true,
                autofillHints: [AutofillHints.password],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
