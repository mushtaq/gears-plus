//> using scala 3.7.2
//> using dep "ch.epfl.lamp::gears:0.2.0"
//> using test.dep com.lihaoyi::utest::0.9.0

// Minimal sensible compiler flags (most important ones not on by default)
//> using options -deprecation  // Show deprecation details
//> using options -feature       // Require imports for advanced features  
//> using options -unchecked     // Enable additional warnings (e.g. type erasure)

// Catch unused code (off by default, but very useful)
//> using options -Wunused:all

// Future-proof the code
//> using options -source:future

// For CI/CD, uncomment this (ensure code has no warnings first):
// //> using options -Werror

